package com.databricks.labs.remorph.generators.orchestration.rules.converted

import com.databricks.labs.remorph.intermediate.workflows.JobNode
import com.databricks.labs.remorph.intermediate.workflows.tasks.NotebookTask

case class PythonNotebookTask(file: CreatedFile, baseParameters: Map[String, String] = Map.empty)
    extends JobNode
    with ToNotebookTask {
  override def children: Seq[JobNode] = Seq(file)
  override def resourceName: String = file.resourceName
  override def toNotebookTask: NotebookTask = NotebookTask(file.name, Some(baseParameters), None)

}
