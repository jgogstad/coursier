package coursier

import java.io.{File, FileNotFoundException, IOException}
import java.net.{HttpURLConnection, URL, URLConnection}

import coursier.cache.CacheUrl
import coursier.core.{Classifier, Type}
import coursier.util.{EitherT, Monad}

object FallbackDependenciesRepository {

  def exists(url: URL, localArtifactsShouldBeCached: Boolean): Boolean = {

    // Sometimes HEAD attempts fail even though standard GETs are fine.
    // E.g. https://github.com/NetLogo/NetLogo/releases/download/5.3.1/NetLogo.jar
    // returning 403s. Hence the second attempt below.

    val protocolSpecificAttemptOpt = {

      def ifFile: Option[Boolean] = {
        if (localArtifactsShouldBeCached && !new File(url.getPath).exists()) {
          val cachePath = coursier.cache.CacheDefaults.location
          // 'file' here stands for the protocol (e.g. it's https instead for https:// URLs)
          Some(new File(cachePath, s"file/${url.getPath}").exists())
        } else {
          Some(new File(url.getPath).exists()) // FIXME Escaping / de-escaping needed here?
        }
      }

      def ifHttp: Option[Boolean] = {
        // HEAD request attempt, adapted from http://stackoverflow.com/questions/22541629/android-how-can-i-make-an-http-head-request/22545275#22545275

        var conn: HttpURLConnection = null
        try {
          conn = url
            .openConnection()
            .asInstanceOf[HttpURLConnection]
          conn.setRequestMethod("HEAD")
          conn.getInputStream.close()
          Some(true)
        }
        catch {
          case _: FileNotFoundException => Some(false)
          case _: IOException           => None // error other than not found
        }
        finally {
          if (conn != null)
            CacheUrl.closeConn(conn)
        }
      }

      url.getProtocol match {
        case "file"           => ifFile
        case "http" | "https" => ifHttp
        case _                => None
      }
    }

    def genericAttempt: Boolean = {
      var conn: URLConnection = null
      try {
        conn = url.openConnection()
        // NOT setting request type to HEAD here.
        conn.getInputStream.close()
        true
      }
      catch {
        case _: IOException => false
      }
      finally {
        if (conn != null)
          CacheUrl.closeConn(conn)
      }
    }

    protocolSpecificAttemptOpt
      .getOrElse(genericAttempt)
  }

}

final case class FallbackDependenciesRepository(
  fallbacks: Map[(Module, String), (URL, Boolean)],
  localArtifactsShouldBeCached: Boolean = false
) extends Repository {

  def find[F[_]](
    module: Module,
    version: String,
    fetch: Repository.Fetch[F]
  )(implicit
    F: Monad[F]
  ): EitherT[F, String, (Artifact.Source, Project)] = {

    val res = fallbacks
      .get((module, version))
      .fold[Either[String, (Artifact.Source, Project)]](Left("No fallback URL found")) {
        case (url, _) =>

          val urlStr = url.toExternalForm
          val idx = urlStr.lastIndexOf('/')

          if (idx < 0 || urlStr.endsWith("/"))
            Left(s"$url doesn't point to a file")
          else {
            val (dirUrlStr, fileName) = urlStr.splitAt(idx + 1)

            if (FallbackDependenciesRepository.exists(url, localArtifactsShouldBeCached)) {
              val proj = Project(
                module,
                version,
                Nil,
                Map.empty,
                None,
                Nil,
                Nil,
                Nil,
                None,
                None,
                None,
                relocated = false,
                None,
                Nil,
                Info.empty
              )

              Right((this, proj))
            } else
              Left(s"$fileName not found under $dirUrlStr")
          }
      }

    EitherT(F.point(res))
  }

  def artifacts(
    dependency: Dependency,
    project: Project,
    overrideClassifiers: Option[Seq[Classifier]]
  ): Seq[(Attributes, Artifact)] =
    fallbacks
      .get(dependency.moduleVersion)
      .toSeq
      .map {
        case (url, changing) =>
          val url0 = url.toString
          val ext = url0.substring(url0.lastIndexOf('.') + 1)
          val attr = Attributes(Type(ext))
          (attr, Artifact(url0, Map.empty, Map.empty, changing, optional = false, None))
      }

}
