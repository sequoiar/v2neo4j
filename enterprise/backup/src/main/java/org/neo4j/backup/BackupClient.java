/**
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.backup;

import static org.neo4j.backup.BackupServer.FRAME_LENGTH;
import static org.neo4j.backup.BackupServer.PROTOCOL_VERSION;

import org.jboss.netty.buffer.ChannelBuffer;

import org.neo4j.com.Client;
import org.neo4j.com.ObjectSerializer;
import org.neo4j.com.Protocol;
import org.neo4j.com.ProtocolVersion;
import org.neo4j.com.RequestContext;
import org.neo4j.com.RequestType;
import org.neo4j.com.Response;
import org.neo4j.com.Serializer;
import org.neo4j.com.TargetCaller;
import org.neo4j.com.storecopy.StoreWriter;
import org.neo4j.com.storecopy.ToNetworkStoreWriter;
import org.neo4j.kernel.impl.nioneo.store.StoreId;
import org.neo4j.kernel.logging.Logging;
import org.neo4j.kernel.monitoring.Monitors;

import java.io.IOException;

class BackupClient extends Client<TheBackupInterface> implements TheBackupInterface
{
    public BackupClient( String hostNameOrIp, int port, Logging logging, Monitors monitors, StoreId storeId )
    {
        super( hostNameOrIp, port, logging, monitors, storeId, FRAME_LENGTH,
                new ProtocolVersion( PROTOCOL_VERSION, ProtocolVersion.INTERNAL_PROTOCOL_VERSION ), 40 * 1000,
                Client.DEFAULT_MAX_NUMBER_OF_CONCURRENT_CHANNELS_PER_CLIENT,
                FRAME_LENGTH );
    }

    public Response<Void> fullBackup( StoreWriter storeWriter, final boolean forensics )
    {
        return sendRequest( BackupRequestType.FULL_BACKUP, RequestContext.EMPTY, new Serializer()
        {
            @Override
            public void write( ChannelBuffer buffer ) throws IOException
            {
                buffer.writeByte( forensics ? (byte) 1 : (byte) 0 );
            }
        }, new Protocol.FileStreamsDeserializer( storeWriter ) );
    }

    public Response<Void> incrementalBackup( RequestContext context )
    {
        return sendRequest( BackupRequestType.INCREMENTAL_BACKUP, context, Protocol.EMPTY_SERIALIZER,
                Protocol.VOID_DESERIALIZER );
    }

    @Override
    protected boolean shouldCheckStoreId( RequestType<TheBackupInterface> type )
    {
        return type != BackupRequestType.FULL_BACKUP;
    }

    public static enum BackupRequestType implements RequestType<TheBackupInterface>
    {
        FULL_BACKUP( new TargetCaller<TheBackupInterface, Void>()
        {
            public Response<Void> call( TheBackupInterface master, RequestContext context,
                    ChannelBuffer input, ChannelBuffer target )
            {
                boolean forensics = input.readable() ? booleanOf( input.readByte() ) : false;
                return master.fullBackup( new ToNetworkStoreWriter( target, new Monitors() ), forensics );
            }

            private boolean booleanOf( byte value )
            {
                switch ( value )
                {
                case 0: return false;
                case 1: return true;
                default: throw new IllegalArgumentException( "Invalid 'boolean' byte value " + value );
                }
            }
        }, Protocol.VOID_SERIALIZER ),
        INCREMENTAL_BACKUP( new TargetCaller<TheBackupInterface, Void>()
        {
            public Response<Void> call( TheBackupInterface master, RequestContext context,
                    ChannelBuffer input, ChannelBuffer target )
            {
                return master.incrementalBackup( context );
            }
        }, Protocol.VOID_SERIALIZER )

        ;
        @SuppressWarnings( "rawtypes" )
        private final TargetCaller masterCaller;
        @SuppressWarnings( "rawtypes" )
        private final ObjectSerializer serializer;

        @SuppressWarnings( "rawtypes" )
        private BackupRequestType( TargetCaller masterCaller, ObjectSerializer serializer )
        {
            this.masterCaller = masterCaller;
            this.serializer = serializer;
        }

        @SuppressWarnings( "rawtypes" )
        public TargetCaller getTargetCaller()
        {
            return masterCaller;
        }

        @SuppressWarnings( "rawtypes" )
        public ObjectSerializer getObjectSerializer()
        {
            return serializer;
        }

        public byte id()
        {
            return (byte) ordinal();
        }
    }
}
