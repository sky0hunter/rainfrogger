package rainfrogger

import zio.*
import zio.test.*
import zio.test.Assertion.*

object TunnelSpec extends ZIOSpecDefault:

  def spec = suite("TunnelSpec")(
    test("buildUrl produces correct URL for direct connection"):
      val db = DbConfig("db.local", 5432, "mydb", "user", "p@ss word")
      val url = Rainfrog.buildUrlForTest(db, None)
      assert(url)(equalTo("postgres://user:p%40ss+word@db.local:5432/mydb"))
    ,
    test("buildUrl uses localhost when tunnel is active"):
      val db     = DbConfig("172.16.0.5", 6432, "prod", "admin", "secret")
      val handle = TunnelHandle("test", 15432, java.nio.file.Paths.get("/tmp/test.sock"), "bastion")
      val url    = Rainfrog.buildUrlForTest(db, Some(handle))
      assert(url)(equalTo("postgres://admin:secret@localhost:15432/prod"))
    ,
    test("ephemeral port is in valid range"):
      for port <- ZIO.attempt(Tunnel.allocatePort)
      yield assert(port)(isGreaterThan(0)) && assert(port)(isLessThanEqualTo(65535))
  )
