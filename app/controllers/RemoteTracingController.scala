package controllers

import java.nio.file._

import akka.actor.{ActorSystem, _}
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import javax.inject.Inject
import play.api.http.Writeable
import play.api.libs.streams.ActorFlow
import play.api.mvc._
import play.api.{Configuration, Logger}
import play.mvc.Http.MimeTypes

import scala.concurrent.{ExecutionContext, Future}

object RemoteTracingController {
  val ForceJsonWriteable: Writeable[String] =
    Writeable(Codec.utf_8.encode, contentType = Some(MimeTypes.JSON))

  val WorkspaceDumpRelPath: Path = Paths.get("workspace-dump.zip")

  def safePathPart(str: String): String = str.replace("_", "__").replace("/", "_s")

  def sessionDataPath(outputRoot: Path, projectId: String, clientId: String, sessionId: String): Path =
    outputRoot.resolve(
      Paths.get(safePathPart(projectId), safePathPart(clientId), safePathPart(sessionId))
    )
}

class RemoteTracingController @Inject()(
    cc: ControllerComponents
)( implicit
   system: ActorSystem,
   mat: Materializer,
   cfg: Configuration,
   ec: ExecutionContext
) extends AbstractController(cc) {
  import RemoteTracingController._

  private val outputRoot: Path = Paths.get(cfg.get[String]("outputDirectory"))
  Files.createDirectories(outputRoot)

  val projects = Action {
    Ok(views.html.listing(
      title = "projects",
      message = "Seen projects:",
      children = immediateChildDirectories(outputRoot).map(_ + "/clients/").toList
    ))
  }

  def clients(projectId: String) = Action {
    val projectPath = outputRoot.resolve(safePathPart(projectId))
    if (!Files.isDirectory(projectPath)) {
      if (Files.exists(projectPath)) {
        Logger.warn(s"Project path exists, but is not a directory: $projectPath")
      }
      BadRequest(s"no such project: $projectId")
    } else {
      Ok(views.html.listing(
        title = s"$projectId: clients",
        message = s"Clients seen with project: $projectId",
        children = immediateChildDirectories(projectPath).map(_ + "/sessions/").toList
      ))
    }
  }

  def sessionData(projectId: String, clientId: String) = Action {
    val clientRef = s"$projectId/$clientId"
    val clientPath = outputRoot.resolve(Paths.get(safePathPart(projectId), safePathPart(clientId)))
    if (!Files.isDirectory(clientPath)) {
      if (Files.exists(clientPath)) {
        Logger.warn(s"Client path exists, but is not a directory: $clientPath")
      }
      NotFound(s"no such client: $clientRef")
    } else {
      Ok(views.html.listing(
        title = s"$clientRef session data",
        message = s"Session data of client: $clientRef",
        children = immediateChildDirectories(clientPath).flatMap { sessionId =>
          Stream(
            LspLogSocketActor.LspLogRelPath,
            RemoteTracingController.WorkspaceDumpRelPath
          )
            .map(clientPath.resolve(sessionId).resolve)
            .filter(Files.isRegularFile(_))
            .map(clientPath.relativize(_).toString)
        }.toList
      ))
    }
  }

  def lspLog(projectId: String, clientId: String, sessionId: String, format: Option[String]) = Action {
    val lspLogPath = sessionDataPath(outputRoot, projectId, clientId, sessionId).resolve(LspLogSocketActor.LspLogRelPath)
    format match {
      case None =>
        if (Files.isRegularFile(lspLogPath)) {
          // TODO: what happens when the file is modified while it is being sent?
          Ok.sendPath(lspLogPath)
        } else {
          if (Files.exists(lspLogPath)) {
            Logger.warn(s"Lsp log path exists, but is not a regular file: $lspLogPath")
          }
          NotFound
        }
      case Some("json") =>
        val sb = new StringBuilder
        sb += '['
        var sentFirst = false
        scala.io.Source.fromFile(lspLogPath.toFile).getLines().foreach { line =>
          if (!sentFirst) sentFirst = true else sb += ','
          // see https://github.com/Microsoft/language-server-protocol-inspector/blob/adb1c488bce8cef818e07c8eced7611fa3775b4a/lsp-inspector/src/logParser/jsonLogParser.ts
          sb ++= line.substring(21)
        }
        sb += ']'
        // TODO: HttpEntity.Streamed/.Chunked ?
        Ok(sb.toString)(RemoteTracingController.ForceJsonWriteable)
      case Some(unrecognized) =>
        BadRequest(s"Unrecognized format: $unrecognized")
    }
  }

  def workspaceDump(projectId: String, clientId: String, sessionId: String) = Action {
    val workspaceDumpPath = sessionDataPath(outputRoot, projectId, clientId, sessionId)
      .resolve(WorkspaceDumpRelPath)

    if (Files.isRegularFile(workspaceDumpPath)) {
      Ok.sendPath(workspaceDumpPath, inline = false)
    } else {
      if (Files.exists(workspaceDumpPath)) {
        Logger.warn(s"Workspace dump path exists, but is not a regular file: $workspaceDumpPath")
      }
      NotFound
    }
  }

  def uploadWorkspaceDump(projectId: String, clientId: String, sessionId: String) =
    Action(parse.temporaryFile) { request =>
      val sessionPath = outputRoot.resolve(Paths.get(projectId, clientId, sessionId))
      val targetPath = sessionPath.resolve(RemoteTracingController.WorkspaceDumpRelPath)
      if (Files.exists(targetPath)) {
        if (!Files.isRegularFile(targetPath))
          Logger.warn(s"Workspace dump path exists, but is not a regular file: $targetPath")
        BadRequest("File already exists.")
      }

      Files.createDirectories(sessionPath)
      request.body.moveTo(targetPath, replace = false)
      Created
    }

  val socket: WebSocket = WebSocket.acceptOrResult[String, String] { request =>
    val res: Either[Result, Flow[String, String, _]] = {
      var errors: List[String] = Nil
      def header(name: String): String = request.headers.get(name) match {
        case Some(value) => value
        case None =>
          errors ::= s"missing header: $name"
          null
      }
      val projectId = header("X-DLS-Project-ID")
      val clientId = header("X-DLS-Client-ID")
      val sessionId = header("X-DLS-Session-ID")
      if (errors.isEmpty) {
        Right(ActorFlow.actorRef(out => Props(new LspLogSocketActor(out, outputRoot, projectId, clientId, sessionId))))
      } else {
        val err = errors.mkString("errors encountered when processing lsp.log socket request:", "\n- ", "")
        Logger.warn(err)
        Left(BadRequest(err))
      }
    }
    Future.successful(res)
  }

  private def immediateChildDirectories(path: Path): Stream[String] = {
    import scala.collection.JavaConverters._
    Files.list(path).iterator().asScala
      .filter(Files.isDirectory(_))
      .map(_.getFileName.toString)
      .toStream
  }
}

object LspLogSocketActor {
  val LspLogRelPath: Path = Paths.get("lsp.log")
}

class LspLogSocketActor(
  out: ActorRef,
  val outputRoot: Path,
  val projectId: String,
  val clientId: String,
  val sessionId: String
) extends Actor {
  private val targetRef = s"$projectId/$clientId/$sessionId"
  private val logOutputStream = {
    val targetPath = RemoteTracingController.sessionDataPath(outputRoot, projectId, clientId, sessionId)
      .resolve(LspLogSocketActor.LspLogRelPath)
    Files.createDirectories(targetPath.getParent)

    // no support for reconnecting sessions - lockfiles would be useful for that
    Files.newOutputStream(targetPath, StandardOpenOption.CREATE_NEW)
  }

  Logger.warn(s"Initialized actor: $targetRef")

  override val receive: PartialFunction[Any, Unit] = {
    case msg: String =>
      if (msg.nonEmpty) logOutputStream.write(msg.getBytes("UTF-8"))
  }

  override def postStop(): Unit = {
    Logger.warn(s"Stopping actor: $targetRef")
    logOutputStream.close()
    super.postStop()
  }
}
