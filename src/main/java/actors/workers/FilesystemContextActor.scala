package actors.workers

import requests.config.{GetContextDataFilePathResponse, GetContextDataFilePath}

import scala.util.control.Breaks._
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

import actors.behaviors._
import actors.workers.models.{KeyMapFilesystemPersistence, KeyMapIndexEntry, KeyMapIndex}
import akka.actor.{ActorRef, Actor}
import akka.event.LoggingReceive
import events.{ConnectFile, DisconnectFile}
import requests._
import utils.BufferedResource

class FilesystemContextActor extends Actor with Requester
                                           with MessageHandler
                                           /*with RequiresSetup*/ {

  private val _fileChannelResource = new BufferedResource[String, FileChannel]("File")

  private var _contextIndex = new KeyMapIndex(context.self.path.name)
  private var _configActorRef : ActorRef = null
  private var _dataFolder = ""

  context.self ! ConnectFile(_dataFolder + context.self.path.name + ".txt")

  def doSetup(x:Setup) {
    _configActorRef = x.configActor
    onResponseOf(new GetContextDataFilePath(context.self.path.name), _configActorRef, context.self, {
      case x:GetContextDataFilePathResponse => _dataFolder = x.path
      case x:ErrorResponse => throw x.ex
    })
  }

  def receive = LoggingReceive({

    // case x:Setup => handleSetup(x)

    case x:ConnectFile =>
      _contextIndex = new KeyMapFilesystemPersistence().load(_dataFolder, context.self.path.name)
      _fileChannelResource.set((a,b,c) => b.apply(new RandomAccessFile(x.path, "rw").getChannel))

    case x:DisconnectFile =>
      _fileChannelResource.reset(Some((channel) => channel.close()))
      new KeyMapFilesystemPersistence().save(_contextIndex, _dataFolder)
    // @todo: There should be a way to notify the caller about the failure of the clean-up action (Request/Response?)

    case x:Shutdown => context.self ! DisconnectFile() // @todo: Make Shutdown use request/response

    //case x:Request => handleRequest(x, sender(), {

      case x:ReadFromContext =>
        readFromDataFile(x.key,
          (data) => {
            sender ! ReadResponse(x, data)
          },
          (error) => sender ! ErrorResponse(x, error))


      case x:WriteToContext =>
        appendToDataFile(x.key, x.value,
          (indexEntry) => _contextIndex.add(indexEntry),
          // @todo: There should be two types of write response: 'accepted' and 'written'
          // the last should be sent here 'whenWritten'
          (exception) => sender ! ErrorResponse(x, exception)
        )
        // @todo: There should be two types of write response: 'accepted' and 'written'
        // where the first would be here
        sender ! WriteResponse(x)
    //}
  //)
  })

  /**
   * Uses the actor's _fileChannelResource to create a MappedByteBuffer which is then
   * supplied to the withBuffer-continuation.
   * @param position The position in the file
   * @param length The length of the buffer
   * @param withBuffer The success continuation
   * @param error The error continuation
   */
  private def withBuffer(position:Int,
                 length:Int,
                 withBuffer:(MappedByteBuffer) => Unit,
                 error:(Exception) => Unit) {

    _fileChannelResource.withResource(
        (channel) => withBuffer.apply(channel.map(FileChannel.MapMode.READ_WRITE, position, length)),
        (exception) => error(exception))
  }

  /**
   * Appends a value to the data file and creates a index entry for it.
   * @param key The key
   * @param data The data
   * @param indexEntry Is called when the corresponding index entry is available
   * @param error Is called in case of an error
   * @return A index entry
   */
  private def appendToDataFile(key:String,
                               data:String,
                               indexEntry: (KeyMapIndexEntry) => Unit,
                               error: (Exception) => Unit) {

    val bytes = data.getBytes("UTF-8")

    val paddedBytes = new Array[Byte](_contextIndex.padBytes(bytes.length))
    val blocks = _contextIndex.pad(paddedBytes.length)
    val targetAddress = _contextIndex.getNextAddressBytes

    val contextIndexEntry = new KeyMapIndexEntry(
      key = key,
      address = _contextIndex.pad(_contextIndex.getNextAddressBytes),
      length = blocks)

    indexEntry(contextIndexEntry)

    if (_contextIndex.getBlockSize != 1)
      // @todo: Find a way to avoid array copy (ByteBuffer?)
      bytes.copyToArray(paddedBytes)

    withBuffer(targetAddress, paddedBytes.length,
      (buffer) => {
        if (_contextIndex.getBlockSize != 1) // @todo: Is there something for what I would need the blocks? throw it out?!
          buffer.put(paddedBytes)
        else
          buffer.put(bytes)

        contextIndexEntry.setProcessed()
      },
      // @todo: Provide simple mechanism to wrap the exception with some information about the current method
      (exception) => error(exception)
    )
  }

  /**
   * Reads data from the data file.
   * @param key The key
   * @return The data
   */
  private def readFromDataFile (key:String, withData:(String) => Unit, error:(Exception) => Unit) {
    val range = _contextIndex.getRangeBytes(key)

    withBuffer(range._1, range._2,
      (buffer) => {
        val paddedData = new Array[Byte](range._2)

        buffer.get(paddedData)

        var data = paddedData

        if (_contextIndex.getBlockSize != 1) {
          // @todo: Find a more elegant way to tell the content size of a block (header?, map of partial blocks?)
          breakable {
            for ((x, i) <- paddedData.view.zipWithIndex) {
              if (x == 0) {
                data = paddedData.slice(0, i)
                break()
              }
            }
          }
        }

        withData(new String(data, "UTF-8"))
      },
      (exception) => error(exception)
    )
  }
}