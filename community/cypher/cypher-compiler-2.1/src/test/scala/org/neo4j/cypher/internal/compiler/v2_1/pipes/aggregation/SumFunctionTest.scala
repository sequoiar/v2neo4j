/**
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v2_1.pipes.aggregation

import org.neo4j.cypher.CypherTypeException
import org.neo4j.cypher.internal.commons.CypherFunSuite
import org.neo4j.cypher.internal.compiler.v2_1.commands.expressions.Expression

class SumFunctionTest extends CypherFunSuite with AggregateTest {
  def createAggregator(inner: Expression) = new SumFunction(inner)

  test("singleValueReturnsThatNumber") {
    val result = aggregateOn(1)

    result should equal(1)
    result shouldBe a [java.lang.Integer]
  }

  test("singleValueOfDecimalReturnsDecimal") {
    val result = aggregateOn(1.0d)

    result should equal(1.0)
    result shouldBe a [java.lang.Double]
  }

  test("mixOfIntAndDoubleYieldsDouble") {
    val result = aggregateOn(1, 1.0d)

    result should equal(2.0)
    result shouldBe a [java.lang.Double]
  }

  test("mixedLotsOfStuff") {
    val result = aggregateOn(1.byteValue(), 1.shortValue())

    result should equal(2)
    result shouldBe a [java.lang.Integer]
  }

  test("noNumbersEqualsZero") {
    val result = aggregateOn()

    result should equal(0)
    result shouldBe a [java.lang.Integer]
  }

  test("nullDoesNotChangeTheSum") {
    val result = aggregateOn(1, null)

    result should equal(1)
    result shouldBe a [java.lang.Integer]
  }

  test("noNumberValuesThrowAnException") {
    intercept[CypherTypeException](aggregateOn(1, "wut"))
  }
}
