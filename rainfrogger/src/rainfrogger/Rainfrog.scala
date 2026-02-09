package rainfrogger

import zio.*

import java.net.URLEncoder

object Rainfrog:

  def launch(db: DbConfig, tunnel: Option[TunnelHandle]): Task[ExitCode] =
    val url = buildUrl(db, tunnel)
    ZIO.attempt:
      val proc = ProcessBuilder("rainfrog", "--url", url)
        .inheritIO()
        .start()
      val rc = proc.waitFor()
      if rc == 0 then ExitCode.success else ExitCode(rc)

  def testConnection(db: DbConfig, tunnel: Option[TunnelHandle]): Task[Unit] =
    val host = tunnel.map(_ => "localhost").getOrElse(db.host)
    val port = tunnel.map(_.localPort).getOrElse(db.port)
    ZIO.attempt:
      val sock = new java.net.Socket(host, port)
      sock.close()
    .mapError(e => new RuntimeException(s"Cannot reach $host:$port: ${e.getMessage}"))

  private[rainfrogger] def buildUrlForTest(db: DbConfig, tunnel: Option[TunnelHandle]): String = buildUrl(db, tunnel)

  private def buildUrl(db: DbConfig, tunnel: Option[TunnelHandle]): String =
    val host = tunnel.map(_ => "localhost").getOrElse(db.host)
    val port = tunnel.map(_.localPort).getOrElse(db.port)
    val encodedPass = URLEncoder.encode(db.password, "UTF-8")
    s"postgres://${db.user}:$encodedPass@$host:$port/${db.name}"
