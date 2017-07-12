/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import akka.annotation.InternalApi
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec }
import akka.testkit._
import com.typesafe.config.ConfigFactory

import scala.collection.immutable
import scala.collection.immutable.SortedSet
import scala.concurrent.duration._

object MultiDcSunnyWeatherMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")
  val fifth = role("fifth")

  nodeConfig(first, second, third)(ConfigFactory.parseString(
    """
    akka {
      cluster.multi-data-center.self-data-center = alpha
    }
    """))

  nodeConfig(fourth, fifth)(ConfigFactory.parseString(
    """
    akka {
      cluster.multi-data-center.self-data-center = beta
    }
    """))

  commonConfig(ConfigFactory.parseString(
    """
    akka {
      actor.provider = cluster
      
      loggers = ["akka.testkit.TestEventListener"]
      loglevel = INFO
      
      remote.log-remote-lifecycle-events = off
      
      cluster {
        debug.verbose-heartbeat-logging = off
       
        multi-data-center {
          cross-data-center-connections = 2
        }
      }
    }
    """))

}

class MultiDcSunnyWeatherMultiJvmNode1 extends MultiDcSunnyWeatherSpec
class MultiDcSunnyWeatherMultiJvmNode2 extends MultiDcSunnyWeatherSpec
class MultiDcSunnyWeatherMultiJvmNode3 extends MultiDcSunnyWeatherSpec
class MultiDcSunnyWeatherMultiJvmNode4 extends MultiDcSunnyWeatherSpec
class MultiDcSunnyWeatherMultiJvmNode5 extends MultiDcSunnyWeatherSpec

abstract class MultiDcSunnyWeatherSpec extends MultiNodeSpec(MultiDcSunnyWeatherMultiJvmSpec)
  with MultiNodeClusterSpec {

  "A normal cluster" must {
    "be healthy" taggedAs LongRunningTest in {

      val observer = TestProbe("alpha-observer")

      // allow all nodes to join:
      awaitClusterUp(roles: _*)

      val crossDcHeartbeatSenderPath = "/system/cluster/core/daemon/crossDcHeartbeatSender"
      val selectCrossDcHeartbeatSender = system.actorSelection(crossDcHeartbeatSenderPath)

      val expectedAlphaHeartbeaterNodes = takeNOldestMembers(_.dataCenter == "alpha", 2)
      val expectedAlphaHeartbeaterRoles = membersAsRoles(expectedAlphaHeartbeaterNodes)

      val expectedBetaHeartbeaterNodes = takeNOldestMembers(_.dataCenter == "beta", 2)
      val expectedBetaHeartbeaterRoles = membersAsRoles(expectedBetaHeartbeaterNodes)

      val expectedNoActiveHeartbeatSenderRoles = roles.toSet -- (expectedAlphaHeartbeaterRoles union expectedBetaHeartbeaterRoles)

      enterBarrier("found-expectations")

      info(s"expectedAlphaHeartbeaterNodes = ${expectedAlphaHeartbeaterNodes.map(_.address.port.get)}")
      info(s"expectedBetaHeartbeaterNodes = ${expectedBetaHeartbeaterNodes.map(_.address.port.get)}")
      info(s"expectedNoActiveHeartbeatSenderRoles = ${expectedNoActiveHeartbeatSenderRoles.map(_.port.get)}")

      expectedAlphaHeartbeaterRoles.size should ===(2)
      expectedBetaHeartbeaterRoles.size should ===(2)

      implicit val sender = observer.ref
      runOn(expectedAlphaHeartbeaterRoles.toList: _*) {
        selectCrossDcHeartbeatSender ! CrossDcHeartbeatSender.ReportStatus()
        val status = observer.expectMsgType[CrossDcHeartbeatSender.MonitoringActive](5.seconds)
      }
      runOn(expectedBetaHeartbeaterRoles.toList: _*) {
        selectCrossDcHeartbeatSender ! CrossDcHeartbeatSender.ReportStatus()
        val status = observer.expectMsgType[CrossDcHeartbeatSender.MonitoringActive](5.seconds)
      }
      runOn(expectedNoActiveHeartbeatSenderRoles.toList: _*) {
        selectCrossDcHeartbeatSender ! CrossDcHeartbeatSender.ReportStatus()
        val status = observer.expectMsgType[CrossDcHeartbeatSender.MonitoringDormant](5.seconds)
      }

      enterBarrier("done")
    }
  }

  /**
   * INTERNAL API
   * Returns `Up` (or in "later" status, like Leaving etc, but never `Joining` or `WeaklyUp`) members,
   * sorted by Member.ageOrdering (from oldest to youngest). This restriction on status is needed to
   * strongly guaratnee the order of "oldest" members, as they're linearized by the order in which they become Up
   * (since marking that transition is a Leader action).
   */
  private def membersByAge(): immutable.SortedSet[Member] =
    SortedSet.empty(Member.ageOrdering)
      .union(cluster.state.members.filter(m ⇒ m.status != MemberStatus.WeaklyUp && m.status != MemberStatus.WeaklyUp))

  /** INTERNAL API */
  @InternalApi
  private[cluster] def takeNOldestMembers(memberFilter: Member ⇒ Boolean, n: Int): immutable.SortedSet[Member] =
    membersByAge()
      .filter(m ⇒ m.status != MemberStatus.Joining && m.status != MemberStatus.WeaklyUp)
      .filter(memberFilter)
      .take(n)

  private def membersAsRoles(ms: immutable.Set[Member]): immutable.Set[RoleName] = {
    val res = ms.flatMap(m ⇒ roleName(m.address))
    require(res.size == ms.size, s"Not all members were converted to roles! Got: ${ms}, found ${res}")
    res
  }
}