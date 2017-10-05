/*
 * Copyright 2017 47 Degrees, LLC. <http://www.47deg.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package freestyle

import org.scalatest.{AsyncWordSpec, Matchers}

import freestyle.implicits._
import freestyle.config._
import freestyle.config.implicits._
import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future}
import cats.instances.either._
import cats.instances.future._
import cats.syntax.cartesian._
import cats.syntax.either._
import classy.config._
import com.typesafe.config.{Config => TypesafeConfig}

class ConfigTests extends AsyncWordSpec with Matchers {

  import algebras._

  implicit override def executionContext = ExecutionContext.Implicits.global

  "Config integration" should {

    "allow configuration to be interleaved inside a program monadic flow" in {
      val program = for {
        _      <- app.nonConfig.x
        config <- app.configM.empty
      } yield config
      program.interpret[Future] map { _ shouldBe a[Config] }
    }

    "allow configuration to parse strings" in {
      val program = for {
        a   <- app.nonConfig.x
        cfg <- app.configM.parseString("{n = 1}")
      } yield cfg.int("n").map(_ + a)
      program.interpret[Future] map { _ shouldBe Right(1 + 1) }
    }

    "allow configuration to load classpath files" in {
      import com.typesafe.config.{ConfigException, ConfigFactory}
      def urlses(cl: ClassLoader): Array[java.net.URL] = cl match {
        case null                       => Array()
        case u: java.net.URLClassLoader => u.getURLs() ++ urlses(cl.getParent)
        case _                          => urlses(cl.getParent)
      }

      val urls = urlses(getClass.getClassLoader)

      urls.map(println)
      println(ConfigFactory.load())
      val a = app.configM.load.interpret[Future]
      println(a)

      a map { _.int("s") shouldBe Right(3) }
    }

    "allow values to be read from a parsed config" in {
      val config = """{n = 1, s = "foo", b = true, xs = ["a", "b"], delay = "1000ms"}"""
      val program = app.configM.parseString(config) map { cfg =>
        (cfg.hasPath("n") |@|
          cfg.int("n") |@|
          cfg.double("n") |@|
          cfg.string("s") |@|
          cfg.boolean("b") |@|
          cfg.stringList("xs") |@|
          cfg.duration("delay", TimeUnit.SECONDS)).tupled
      }
      program.interpret[Future] map {
        _ shouldBe Right((true, 1, 1d, "foo", true, List("a", "b"), 1L))
      }
    }

    "allow nested config to be read from a parsed config" in {
      val config1 =
        """{n = 1, config2: {n2 = 2, s = "bar"}, s = "foo", b = true, xs = ["a", "b"], delay = "1000ms"}"""
      val program = app.configM.parseString(config1) map { cfg =>
        (cfg.hasPath("n") |@|
          cfg.int("n") |@|
          cfg.config("config2").map { cfg2 =>
            (cfg2.int("n2") |@|
              cfg2.string("s")).tupled
          } |@|
          cfg.double("n") |@|
          cfg.string("s") |@|
          cfg.boolean("b") |@|
          cfg.stringList("xs") |@|
          cfg.duration("delay", TimeUnit.SECONDS)).tupled
      }

      program.interpret[Future] map {
        _ shouldBe Right((true, 1, Right((2, "bar")), 1d, "foo", true, List("a", "b"), 1L))
      }
    }

    "allow configuration to load classpath files and convert into case class" in {
      case class MyConfig(s: Int)
      implicit val decoder = readConfig[Int]("s") map MyConfig.apply
      app.configM.loadAs[MyConfig].interpret[Future] map { _ shouldBe MyConfig(3) }
    }

    "allow configuration to parse strings and convert into case class" in {
      case class MyConfig(n: Int, s: String, b: Boolean)
      implicit val decoder = for {
        n <- readConfig[Int]("n")
        s <- readConfig[String]("s")
        b <- readConfig[Boolean]("b")
      } yield MyConfig(n, s, b)

      val config = """{n = 1, s = "foo", b = true}"""

      app.configM.parseStringAs[MyConfig](config).interpret[Future] map {
        _ shouldBe MyConfig(1, "foo", true)
      }
    }
  }

}

object algebras {
  @free
  trait NonConfig {
    def x: FS[Int]
  }

  implicit def nonConfigHandler: NonConfig.Handler[Future] =
    new NonConfig.Handler[Future] {
      def x: Future[Int] = Future.successful(1)
    }

  @module
  trait App {
    val nonConfig: NonConfig
    val configM: ConfigM
  }

  val app = App[App.Op]

}
