package com.databricks.labs.remorph.intermediate.workflows.sql

import com.databricks.labs.remorph.intermediate.workflows.JobNode
import com.databricks.sdk.service.jobs

case class SqlTaskSubscription(destinationId: Option[String], userName: Option[String] = None) extends JobNode {
  override def children: Seq[JobNode] = Seq()
  def toSDK: jobs.SqlTaskSubscription = {
    val raw = new jobs.SqlTaskSubscription()
    raw
  }
}