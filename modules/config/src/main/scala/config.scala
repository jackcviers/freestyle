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

import cats.MonadError
import cats.syntax.either._
import classy._
import classy.config._
import com.typesafe.config.{ConfigException, ConfigFactory}

import scala.collection.JavaConverters._
import scala.concurrent.duration._

object config {

  case class ConfigError(msg: String, underlying: Option[Throwable] = None) extends Throwable(msg) {
    underlying foreach initCause
  }

  sealed abstract class Config {
    def hasPath(path: String): Config.Result[Boolean]
    def config(path: String): Config.Result[Config]
    def string(path: String): Config.Result[String]
    def boolean(path: String): Config.Result[Boolean]
    def int(path: String): Config.Result[Int]
    def double(path: String): Config.Result[Double]
    def stringList(path: String): Config.Result[List[String]]
    def duration(path: String, unit: TimeUnit): Config.Result[Long]
  }

  object Config {
    type Result[A] = Either[ConfigError, A]
  }

  @free sealed trait ConfigM {
    def load: FS[Config]
    def empty: FS[Config]
    def parseString(s: String): FS[Config]
    def loadAs[T: ConfigDecoder]: FS[T]
    def parseStringAs[T: ConfigDecoder](s: String): FS[T]
  }

  object implicits {

    private[config] def loadConfig(c: com.typesafe.config.Config): Config = new Config {

      def hasPath(path: String): Config.Result[Boolean] = catchConfig(c.hasPath(path))
      def config(path: String): Config.Result[Config]   = catchConfig(loadConfig(c.getConfig(path)))
      def string(path: String): Config.Result[String]   = catchConfig(c.getString(path))
      def boolean(path: String): Config.Result[Boolean] = catchConfig(c.getBoolean(path))
      def int(path: String): Config.Result[Int]         = catchConfig(c.getInt(path))
      def double(path: String): Config.Result[Double]   = catchConfig(c.getDouble(path))
      def stringList(path: String): Config.Result[List[String]] =
        catchConfig(c.getStringList(path).asScala.toList)
      def duration(path: String, unit: TimeUnit): Config.Result[Long] =
        catchConfig(c.getDuration(path, unit))
    }

    private[config] def catchConfig[A](a: => A): Config.Result[A] =
      Either.catchOnly[ConfigException](a).leftMap(e => ConfigError(e.getMessage, Option(e)))

    private[config] def toConfigError[A](a: => Either[DecodeError, A]): Either[ConfigError, A] =
      a.leftMap(e => ConfigError(e.toPrettyString))

    private[config] lazy val underlying = loadConfig(ConfigFactory.load())

    implicit def freestyleConfigHandler[M[_]](
        implicit ME: MonadError[M, Throwable]): ConfigM.Handler[M] =
      new ConfigM.Handler[M] {
        def load: M[Config] =
          ME.pure({
            def urlses(cl: ClassLoader): Array[java.net.URL] = cl match {
              case null                       => Array()
              case u: java.net.URLClassLoader => u.getURLs() ++ urlses(cl.getParent)
              case _                          => urlses(cl.getParent)
            }

            val urls = urlses(getClass.getClassLoader)

            urls.map(println)

            println(ConfigFactory.load())
            underlying
          })
        def empty: M[Config] = ME.pure(loadConfig(ConfigFactory.empty()))
        def parseString(s: String): M[Config] =
          ME.catchNonFatal(loadConfig(ConfigFactory.parseString(s)))
        def loadAs[T](decoder: ConfigDecoder[T]): M[T] =
          toConfigError(decoder.load()).fold(ME.raiseError, ME.pure)
        def parseStringAs[T](s: String, decoder: ConfigDecoder[T]): M[T] =
          toConfigError(decoder.fromString(s)).fold(ME.raiseError, ME.pure)
      }
  }

}
