package com.databricks.labs.remorph.generators.sql

import com.databricks.labs.remorph.parsers.{intermediate => ir}
import com.databricks.labs.remorph.generators.{Generator, GeneratorContext}
import com.databricks.labs.remorph.parsers.intermediate.{ExceptSetOp, IntersectSetOp, UnionSetOp}

class LogicalPlanGenerator(val explicitDistinct: Boolean = false) extends Generator[ir.LogicalPlan, String] {

  private val expr = new ExpressionGenerator

  override def generate(ctx: GeneratorContext, tree: ir.LogicalPlan): String = tree match {
    case b: ir.Batch => b.children.map(generate(ctx, _)).mkString("", ";\n", ";")
    case ir.WithCTE(ctes, query) =>
      s"WITH ${ctes.map(generate(ctx, _))} ${generate(ctx, query)}"
    case ir.Project(input, expressions) =>
      s"SELECT ${expressions.map(expr.generate(ctx, _)).mkString(",")} FROM ${generate(ctx, input)}"
    case ir.NamedTable(id, _, _) => id
    case ir.Filter(input, condition) =>
      s"${generate(ctx, input)} WHERE ${expr.generate(ctx, condition)}"
    case ir.Limit(input, limit) =>
      s"${generate(ctx, input)} LIMIT ${expr.generate(ctx, limit)}"
    case join: ir.Join => generateJoin(ctx, join)
    case setOp: ir.SetOperation => setOperation(ctx, setOp)
    case x => throw unknown(x)
  }

  private def generateJoin(ctx: GeneratorContext, join: ir.Join): String = {
    val left = generate(ctx, join.left)
    val right = generate(ctx, join.right)
    val joinType = join.join_type match {
      case ir.InnerJoin => "INNER JOIN"
      case ir.FullOuterJoin => "FULL OUTER JOIN"
      case ir.LeftOuterJoin => "LEFT OUTER JOIN"
      case ir.LeftSemiJoin => "LEFT SEMI JOIN"
      case ir.LeftAntiJoin => "LEFT ANTI JOIN"
      case ir.RightOuterJoin => "RIGHT OUTER JOIN"
      case ir.CrossJoin => "JOIN"
    }

    val conditionOpt = join.join_condition.map(expr.generate(ctx, _))
    val spaceAndCondition = conditionOpt.map(" ON " + _).getOrElse("")
    val using = join.using_columns.mkString(", ")
    val spaceAndUsing = if (using.isEmpty) "" else s" USING $using"
    s"$left $joinType $right$spaceAndCondition$spaceAndUsing"
  }

  private def setOperation(ctx: GeneratorContext, setOp: ir.SetOperation): String = {
    if (setOp.allow_missing_columns) {
      throw unknown(setOp)
    }
    if (setOp.by_name) {
      throw unknown(setOp)
    }
    val op = setOp.set_op_type match {
      case UnionSetOp => "UNION"
      case IntersectSetOp => "INTERSECT"
      case ExceptSetOp => "EXCEPT"
      case _ => throw unknown(setOp)
    }
    val duplicates = if (setOp.is_all) " ALL" else if (explicitDistinct) " DISTINCT" else ""
    s"(${generate(ctx, setOp.left)}) $op$duplicates (${generate(ctx, setOp.right)})"
  }
}