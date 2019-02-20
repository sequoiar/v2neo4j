/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
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
package org.neo4j.kernel.impl.query;

import org.neo4j.graphdb.QueryStatistics;
import org.neo4j.values.AnyValue;

public interface QuerySubscriber
{
    void newRecord();
    void onValue( int offset, AnyValue value );
    void closeRecord() throws Exception;

    void onError( Throwable throwable );
    void onCompleted( QueryStatistics statistics );

    QuerySubscriber NOT_A_SUBSCRIBER = new QuerySubscriber()
    {
        @Override
        public void newRecord()
        {
            throwError();
        }

        @Override
        public void onValue( int offset, AnyValue value )
        {
            throwError();
        }

        @Override
        public void closeRecord()
        {
            throwError();
        }

        @Override
        public void onError( Throwable throwable )
        {
            throwError();
        }

        @Override
        public void onCompleted( QueryStatistics statistics )
        {
            throwError();
        }

        private void throwError()
        {
            throw new UnsupportedOperationException( "Invalid operation, can't use this as a subscriber" );
        }
    };
}