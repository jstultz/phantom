/*
 * Copyright 2013 - 2017 Outworkers Ltd.
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
package com.outworkers.phantom.column

import scala.util.{Success, Try}
import com.outworkers.phantom.{CassandraTable, Row}
import com.outworkers.phantom.builder.QueryBuilder
import com.outworkers.phantom.builder.primitives.Primitive
import com.outworkers.phantom.builder.primitives.Primitives.StringPrimitive
import com.outworkers.phantom.builder.query.engine.CQLQuery
import com.outworkers.phantom.builder.syntax.CQLSyntax

sealed trait JsonDefinition[T] {

  def fromJson(obj: String): T

  def toJson(obj: T): String

  def valueAsCql(obj: T): String = CQLQuery.empty.singleQuote(toJson(obj))
}

abstract class JsonColumn[
  T <: CassandraTable[T, R],
  R,
  ValueType
](table: CassandraTable[T, R]) extends Column[T, R, ValueType](table) with JsonDefinition[ValueType] {

  def asCql(value: ValueType): String = CQLQuery.empty.singleQuote(toJson(value))

  val cassandraType = CQLSyntax.Types.Text

  def parse(row: Row): Try[ValueType] = {
    Try(fromJson(StringPrimitive.deserialize(row.getBytesUnsafe(name), row.version)))
  }
}

abstract class OptionalJsonColumn[
  T <: CassandraTable[T, R],
  R,
  ValueType
](table: CassandraTable[T, R]) extends OptionalColumn[T, R, ValueType](table) with JsonDefinition[ValueType] {

  def asCql(value: Option[ValueType]): String = value match {
    case Some(json) => CQLQuery.empty.singleQuote(toJson(json))
    case None => CQLQuery.empty.singleQuote("")
  }

  val cassandraType = CQLSyntax.Types.Text

  override def optional(r: Row): Try[ValueType] = {
    Try(fromJson(StringPrimitive.deserialize(r.getBytesUnsafe(name), r.version)))
  }
}

abstract class JsonListColumn[
  T <: CassandraTable[T, R],
  R,
  ValueType
](table: CassandraTable[T, R])(
  implicit ev: Primitive[String],
  cp: Primitive[List[String]]
) extends AbstractColColumn[T, R, List, ValueType](table) with JsonDefinition[ValueType] {

  override def asCql(v: List[ValueType]): String = cp.asCql(v map toJson)

  override def valueAsCql(obj: ValueType): String = CQLQuery.empty.singleQuote(toJson(obj))

  override val cassandraType = cp.cassandraType

  override def parse(r: Row): Try[List[ValueType]] = {
    if (r.isNull(name)) {
      Success(List.empty[ValueType])
    } else {
      cp.fromRow(name, r) map (_.map(fromJson))
    }
  }
}

abstract class JsonSetColumn[T <: CassandraTable[T, R], R, ValueType](
  table: CassandraTable[T, R]
)(
  implicit ev: Primitive[String],
  cp: Primitive[Set[String]]
) extends AbstractColColumn[T ,R, Set, ValueType](table) with JsonDefinition[ValueType] {

  override def asCql(v: Set[ValueType]): String = cp.asCql(v map toJson)

  override val cassandraType = cp.cassandraType

  override def parse(r: Row): Try[Set[ValueType]] = {
    if (r.isNull(name)) {
      Success(Set.empty[ValueType])
    } else {
      cp.fromRow(name, r) map (_.map(fromJson))
    }
  }
}

abstract class JsonMapColumn[
  Owner <: CassandraTable[Owner, Record],
  Record,
  KeyType,
  ValueType
](table: CassandraTable[Owner, Record])(
  implicit keyPrimitive: Primitive[KeyType],
  strPrimitive: Primitive[String],
  ev: Primitive[Map[KeyType, String]]
) extends AbstractMapColumn[Owner, Record, KeyType, ValueType](table) with JsonDefinition[ValueType] {

  override def keyAsCql(v: KeyType): String = keyPrimitive.asCql(v)

  override val cassandraType = QueryBuilder.Collections.mapType(
    keyPrimitive.cassandraType,
    strPrimitive.cassandraType
  ).queryString

  override def qb: CQLQuery = CQLQuery(name).forcePad.append(cassandraType)

  override def parse(r: Row): Try[Map[KeyType,ValueType]] = {
    if (r.isNull(name)) {
      Success(Map.empty[KeyType, ValueType])
    } else {
      Try(ev.deserialize(r.getBytesUnsafe(name), r.version).mapValues(fromJson))
    }
  }
}
