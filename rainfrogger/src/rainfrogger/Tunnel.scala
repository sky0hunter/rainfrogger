package rainfrogger

import zio.*

import java.net.Socket
import java.nio.file.{Files, Path}
import scala.util.Using

case class TunnelHandle(profile: String, localPort: Int, socketPath: Path, tunnelHost: String)

object Tunnel:

  def start(profile: String, db: DbConfig, tunnel: TunnelConfig): Task[TunnelHandle] =
    val localPort  = tunnel.localPort.getOrElse(ephemeralPort)
    val socketPath = AppConfig.tunnelDir.resolve(s"$profile.sock")
    for
      _ <- ZIO.attempt(Files.createDirectories(AppConfig.tunnelDir))
      _ <- cleanStaleSocket(socketPath)
      _ <- startSsh(db, tunnel, localPort, socketPath)
      _ <- waitForPort(localPort)
    yield TunnelHandle(profile, localPort, socketPath, tunnel.host)

  def stop(handle: TunnelHandle): UIO[Unit] =
    val exit = ProcessBuilder(
      "ssh",
      "-S", handle.socketPath.toString,
      "-O", "exit",
      handle.tunnelHost
    )
    ZIO
      .attempt:
        exit.redirectErrorStream(true).start().waitFor()
        Files.deleteIfExists(handle.socketPath)
      .ignore

  private def startSsh(db: DbConfig, tunnel: TunnelConfig, localPort: Int, socketPath: Path): Task[Unit] =
    ZIO.attempt:
      val forwarding = s"$localPort:${db.host}:${db.port}"
      val cmd = Seq(
        "ssh",
        "-f", "-N",
        "-M", "-S", socketPath.toString,
        "-o", "ExitOnForwardFailure=yes",
        "-o", "ServerAliveInterval=30",
        "-o", "ServerAliveCountMax=3",
        "-L", forwarding,
        "-p", tunnel.port.toString,
        s"${tunnel.user}@${tunnel.host}"
      )
      val proc = ProcessBuilder(cmd*).redirectErrorStream(true).start()
      val output = String(proc.getInputStream.readAllBytes())
      val rc = proc.waitFor()
      if rc != 0 then throw new RuntimeException(s"SSH tunnel failed (exit $rc): $output")

  private def waitForPort(port: Int, timeout: Duration = 5.seconds): Task[Unit] =
    val check = ZIO.attempt:
      Using(new Socket("localhost", port))(_ => ()).isSuccess
    check.flatMap:
      case true  => ZIO.unit
      case false => ZIO.fail(new RuntimeException("port not ready"))
    .retry(Schedule.spaced(200.millis) && Schedule.recurs((timeout.toMillis / 200).toInt))
      .mapError(_ => new RuntimeException(s"Tunnel did not become ready on port $port within $timeout"))

  private def cleanStaleSocket(path: Path): Task[Unit] =
    ZIO.when(Files.exists(path)):
      ZIO.attempt(Files.delete(path))
    .unit

  private[rainfrogger] def allocatePort: Int = ephemeralPort

  private def ephemeralPort: Int =
    val s = new java.net.ServerSocket(0)
    try s.getLocalPort
    finally s.close()
