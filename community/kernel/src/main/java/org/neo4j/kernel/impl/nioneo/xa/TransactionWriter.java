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
package org.neo4j.kernel.impl.nioneo.xa;

import static java.lang.System.currentTimeMillis;
import static org.neo4j.kernel.impl.nioneo.store.Record.NO_NEXT_PROPERTY;
import static org.neo4j.kernel.impl.nioneo.store.Record.NO_PREV_RELATIONSHIP;
import static org.neo4j.kernel.impl.nioneo.store.TokenStore.NAME_STORE_BLOCK_SIZE;
import static org.neo4j.kernel.impl.transaction.XidImpl.DEFAULT_SEED;
import static org.neo4j.kernel.impl.transaction.XidImpl.getNewGlobalId;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

import javax.transaction.xa.Xid;

import org.neo4j.kernel.impl.nioneo.store.AbstractBaseRecord;
import org.neo4j.kernel.impl.nioneo.store.AbstractRecordStore;
import org.neo4j.kernel.impl.nioneo.store.DynamicRecord;
import org.neo4j.kernel.impl.nioneo.store.LabelTokenRecord;
import org.neo4j.kernel.impl.nioneo.store.NeoStore;
import org.neo4j.kernel.impl.nioneo.store.NeoStoreRecord;
import org.neo4j.kernel.impl.nioneo.store.NodeRecord;
import org.neo4j.kernel.impl.nioneo.store.PropertyKeyTokenRecord;
import org.neo4j.kernel.impl.nioneo.store.PropertyRecord;
import org.neo4j.kernel.impl.nioneo.store.PropertyStore;
import org.neo4j.kernel.impl.nioneo.store.RelationshipGroupRecord;
import org.neo4j.kernel.impl.nioneo.store.RelationshipRecord;
import org.neo4j.kernel.impl.nioneo.store.RelationshipTypeTokenRecord;
import org.neo4j.kernel.impl.nioneo.store.SchemaStore;
import org.neo4j.kernel.impl.nioneo.store.TokenRecord;
import org.neo4j.kernel.impl.nioneo.xa.command.Command;
import org.neo4j.kernel.impl.nioneo.xa.command.CommandRecordVisitor;
import org.neo4j.kernel.impl.nioneo.xa.command.PhysicalLogNeoXaCommandWriter;
import org.neo4j.kernel.impl.transaction.XidImpl;
import org.neo4j.kernel.impl.transaction.xaframework.LogBuffer;
import org.neo4j.kernel.impl.transaction.xaframework.LogEntry;
import org.neo4j.kernel.impl.transaction.xaframework.LogEntryWriterv1;
import org.neo4j.kernel.impl.transaction.xaframework.XaCommand;

/**
 * This class lives here instead of somewhere else in order to be able to access the {@link Command} implementations.
 *
 * @author Tobias Lindaaker
 */
public class TransactionWriter
{
    private final int identifier;
    private final int localId;
    private final Output output;

    public TransactionWriter( LogBuffer buffer, int identifier, int localId )
    {
        this( new LogBufferOutput( buffer ), identifier, localId );
    }

    public TransactionWriter( Output output, int identifier, int localId )
    {
        this.output = output;
        this.identifier = identifier;
        this.localId = localId;
    }

    // Transaction coordination

    public void start( int masterId, int myId, long latestCommittedTxWhenTxStarted ) throws IOException
    {
        start( getNewGlobalId( DEFAULT_SEED, localId ), masterId, myId, currentTimeMillis(), latestCommittedTxWhenTxStarted );
    }

    public void start( byte[] globalId, int masterId, int myId, long startTimestamp,
                       long latestCommittedTxWhenTxStarted ) throws IOException
    {
        Xid xid = new XidImpl( globalId, NeoStoreXaDataSource.BRANCH_ID );
        output.writeStart( xid, this.identifier, masterId, myId, startTimestamp, latestCommittedTxWhenTxStarted );
    }

    public void prepare() throws IOException
    {
        prepare( System.currentTimeMillis() );
    }

    public void prepare( long prepareTimestamp ) throws IOException
    {
        output.writePrepare( this.identifier, prepareTimestamp );
    }

    public void commit( boolean twoPhase, long txId ) throws IOException
    {
        commit( twoPhase, txId, System.currentTimeMillis() );
    }

    public void commit( boolean twoPhase, long txId, long commitTimestamp ) throws IOException
    {
        output.writeCommit( this.identifier, twoPhase, txId, commitTimestamp );
    }

    public void done() throws IOException
    {
        output.writeDone( this.identifier );
    }

    // Transaction data

    public void propertyKey( int id, String key, int... dynamicIds ) throws IOException
    {
        Command.PropertyKeyTokenCommand command = new Command.PropertyKeyTokenCommand();
        command.init( withName( new PropertyKeyTokenRecord( id ), dynamicIds, key ) );
        write( command );
    }

    public void label( int id, String name, int... dynamicIds ) throws IOException
    {
        Command.LabelTokenCommand command = new Command.LabelTokenCommand();
        command.init( withName( new LabelTokenRecord( id ), dynamicIds, name ) );
        write( command );
    }

    public void relationshipType( int id, String label, int... dynamicIds ) throws IOException
    {
        Command.RelationshipTypeTokenCommand command = new Command.RelationshipTypeTokenCommand();
        command.init( withName( new RelationshipTypeTokenRecord( id ), dynamicIds, label ) );
        write( command );
    }

    public void update( NeoStoreRecord record ) throws IOException
    {
        Command.NeoStoreCommand command = new Command.NeoStoreCommand();
        command.init( record );
        write( command );
    }

    public void update( LabelTokenRecord labelToken ) throws IOException
    {
        Command.LabelTokenCommand command = new Command.LabelTokenCommand();
        command.init( labelToken );
        write( command );
    }

    public void create( NodeRecord node ) throws IOException
    {
        node.setCreated();
        update( new NodeRecord( node.getId(), false, NO_PREV_RELATIONSHIP.intValue(), NO_NEXT_PROPERTY.intValue() ), node );
    }

    public void create( LabelTokenRecord labelToken ) throws IOException
    {
        labelToken.setCreated();
        update( labelToken );
    }

    public void create( PropertyKeyTokenRecord token ) throws IOException
    {
        token.setCreated();
        update( token );
    }

    public void create( RelationshipGroupRecord group ) throws IOException
    {
        group.setCreated();
        update( group );
    }

    public void update( NodeRecord before, NodeRecord node ) throws IOException
    {
        node.setInUse( true );
        add( before, node );
    }

    public void update( PropertyKeyTokenRecord token ) throws IOException
    {
        token.setInUse( true );
        add( token );
    }

    public void delete( NodeRecord node ) throws IOException
    {
        node.setInUse( false );
        add( node, new NodeRecord( node.getId(), false, NO_PREV_RELATIONSHIP.intValue(), NO_NEXT_PROPERTY.intValue() ) );
    }

    public void create( RelationshipRecord relationship ) throws IOException
    {
        relationship.setCreated();
        update( relationship );
    }

    public void delete( RelationshipGroupRecord group ) throws IOException
    {
        group.setInUse( false );
        add( group );
    }

    public void createSchema( Collection<DynamicRecord> beforeRecord, Collection<DynamicRecord> afterRecord ) throws IOException
    {
        for ( DynamicRecord record : afterRecord )
        {
            record.setCreated();
        }
        updateSchema( beforeRecord, afterRecord );
    }

    public void updateSchema(Collection<DynamicRecord> beforeRecords, Collection<DynamicRecord> afterRecords) throws IOException
    {
        for ( DynamicRecord record : afterRecords )
        {
            record.setInUse( true );
        }
        addSchema( beforeRecords, afterRecords );
    }

    public void update( RelationshipRecord relationship ) throws IOException
    {
        relationship.setInUse( true );
        add( relationship );
    }

    public void update( RelationshipGroupRecord group ) throws IOException
    {
        group.setInUse( true );
        add( group );
    }

    public void delete( RelationshipRecord relationship ) throws IOException
    {
        relationship.setInUse( false );
        add( relationship );
    }

    public void create( PropertyRecord property ) throws IOException
    {
        property.setCreated();
        PropertyRecord before = new PropertyRecord( property.getLongId() );
        if ( property.isNodeSet() )
        {
            before.setNodeId( property.getNodeId() );
        }
        if ( property.isRelSet() )
        {
            before.setRelId( property.getRelId() );
        }
        update( before, property );
    }

    public void update( PropertyRecord before, PropertyRecord after ) throws IOException
    {
        after.setInUse(true);
        add( before, after );
    }

    public void delete( PropertyRecord before, PropertyRecord after ) throws IOException
    {
        after.setInUse(false);
        add( before, after );
    }

    // Internals

    private void addSchema( Collection<DynamicRecord> beforeRecords, Collection<DynamicRecord> afterRecords ) throws IOException
    {
        Command.SchemaRuleCommand command = new Command.SchemaRuleCommand();
        command.init( beforeRecords, afterRecords, null, Long.MAX_VALUE );
        write( command );
    }

    public void add( NodeRecord before, NodeRecord after ) throws IOException
    {
        Command.NodeCommand command = new Command.NodeCommand();
        command.init(  before, after );
        write( command );
    }

    public void add( RelationshipRecord relationship ) throws IOException
    {
        Command.RelationshipCommand command = new Command.RelationshipCommand();
        command.init( relationship );
        write( command );
    }

    public void add( RelationshipGroupRecord group ) throws IOException
    {
        Command.RelationshipGroupCommand command = new Command.RelationshipGroupCommand();
        command.init( group );
        write( command );
    }

    public void add( PropertyRecord before, PropertyRecord property ) throws IOException
    {
        Command.PropertyCommand command = new Command.PropertyCommand();
        command.init( before, property );
        write( command );
    }

    public void add( RelationshipTypeTokenRecord record ) throws IOException
    {
        Command.RelationshipTypeTokenCommand command = new Command.RelationshipTypeTokenCommand();
        command.init( record );
        write( command );
    }

    public void add( PropertyKeyTokenRecord record ) throws IOException
    {
        Command.PropertyKeyTokenCommand command = new Command.PropertyKeyTokenCommand();
        command.init( record );
        write( command );
    }

    public void add( NeoStoreRecord record ) throws IOException
    {
        Command.NeoStoreCommand command = new Command.NeoStoreCommand();
        command.init( record );
        write( command );
    }

    private void write( Command command ) throws IOException
    {
        output.writeCommand( this.identifier, command );
    }

    private static <T extends TokenRecord> T withName( T record, int[] dynamicIds, String name )
    {
        if ( dynamicIds == null || dynamicIds.length == 0 )
        {
            throw new IllegalArgumentException( "No dynamic records for storing the name." );
        }
        record.setInUse( true );
        byte[] data = PropertyStore.encodeString( name );
        if ( data.length > dynamicIds.length * NAME_STORE_BLOCK_SIZE )
        {
            throw new IllegalArgumentException(
                    String.format( "[%s] is too long to fit in %d blocks", name, dynamicIds.length ) );
        }
        else if ( data.length <= (dynamicIds.length - 1) * NAME_STORE_BLOCK_SIZE )
        {
            throw new IllegalArgumentException(
                    String.format( "[%s] is to short to fill %d blocks", name, dynamicIds.length ) );
        }

        for ( int i = 0; i < dynamicIds.length; i++ )
        {
            byte[] part = new byte[Math.min( NAME_STORE_BLOCK_SIZE, data.length - i * NAME_STORE_BLOCK_SIZE )];
            System.arraycopy( data, i * NAME_STORE_BLOCK_SIZE, part, 0, part.length );

            DynamicRecord dynamicRecord = new DynamicRecord( dynamicIds[i] );
            dynamicRecord.setInUse( true );
            dynamicRecord.setData( part );
            dynamicRecord.setCreated();
            record.addNameRecord( dynamicRecord );
        }
        record.setNameId( dynamicIds[0] );
        return record;
    }

    public interface Output
    {
        void writeStart( Xid xid, int identifier, int masterId, int myId, long startTimestamp,
                long latestCommittedTxWhenTxStarted ) throws IOException;

        void writeCommand( int identifier, XaCommand command ) throws IOException;

        void writePrepare( int identifier, long prepareTimestamp ) throws IOException;

        void writeCommit( int identifier, boolean twoPhase, long txId, long commitTimestamp ) throws IOException;

        void writeDone( int identifier ) throws IOException;
    }

    public static class RecordOutput implements Output
    {
        private final CommandRecordVisitor visitor;

        public RecordOutput( CommandRecordVisitor visitor )
        {
            this.visitor = visitor;
        }

        @Override
        public void writeStart( Xid xid, int identifier, int masterId, int myId, long startTimestamp,
                long latestCommittedTxWhenTxStarted ) throws IOException
        {   // Do nothing
        }

        @Override
        public void writeCommand( int identifier, XaCommand command ) throws IOException
        {
            ((Command)command).accept( visitor );
        }

        @Override
        public void writePrepare( int identifier, long prepareTimestamp ) throws IOException
        {   // Do nothing
        }

        @Override
        public void writeCommit( int identifier, boolean twoPhase, long txId, long commitTimestamp ) throws IOException
        {   // Do nothing
        }

        @Override
        public void writeDone( int identifier ) throws IOException
        {   // Do nothing
        }
    }

    public static class NeoStoreCommandRecordVisitor implements CommandRecordVisitor
    {
        private final NeoStore neoStore;

        public NeoStoreCommandRecordVisitor( NeoStore neoStore )
        {
            this.neoStore = neoStore;
        }

        private <RECORD extends AbstractBaseRecord,STORE extends AbstractRecordStore<RECORD>> void update( STORE store, RECORD record )
        {
            boolean prev = neoStore.isInRecoveryMode();
            neoStore.setRecoveredStatus( true );
            try
            {
                store.updateRecord( record );
            }
            finally
            {
                neoStore.setRecoveredStatus( prev );
            }
        }

        @Override
        public void visitNode( NodeRecord record )
        {
            update( neoStore.getNodeStore(), record );
        }

        @Override
        public void visitRelationship( RelationshipRecord record )
        {
            update( neoStore.getRelationshipStore(), record );
        }

        @Override
        public void visitRelationshipGroup( RelationshipGroupRecord record )
        {
            update( neoStore.getRelationshipGroupStore(), record );
        }

        @Override
        public void visitProperty( PropertyRecord record )
        {
            update( neoStore.getPropertyStore(), record );
        }

        @Override
        public void visitRelationshipTypeToken( RelationshipTypeTokenRecord record )
        {
            update( neoStore.getRelationshipTypeStore(), record );
        }

        @Override
        public void visitLabelToken( LabelTokenRecord record )
        {
            update( neoStore.getLabelTokenStore(), record );
        }

        @Override
        public void visitPropertyKeyToken( PropertyKeyTokenRecord record )
        {
            update( neoStore.getPropertyStore().getPropertyKeyTokenStore(), record );
        }

        @Override
        public void visitNeoStore( NeoStoreRecord record )
        {
            boolean prev = neoStore.isInRecoveryMode();
            neoStore.setRecoveredStatus( true );
            try
            {
                neoStore.setGraphNextProp( record.getNextProp() );
            }
            finally
            {
                neoStore.setRecoveredStatus( prev );
            }
        }

        @Override
        public void visitSchemaRule( Collection<DynamicRecord> records )
        {
            boolean prev = neoStore.isInRecoveryMode();
            neoStore.setRecoveredStatus( true );
            try
            {
                SchemaStore schemaStore = neoStore.getSchemaStore();
                for ( DynamicRecord record : records )
                {
                    schemaStore.updateRecord( record );
                }
            }
            finally
            {
                neoStore.setRecoveredStatus( prev );
            }
        }
    }

    public static class LogBufferOutput implements Output
    {
        private final LogBuffer buffer;
        private final LogEntryWriterv1 logEntryWriter = new LogEntryWriterv1();

        public LogBufferOutput( LogBuffer buffer )
        {
            this.buffer = buffer;
            logEntryWriter.setCommandWriter( new PhysicalLogNeoXaCommandWriter() );
        }

        @Override
        public void writeStart( Xid xid, int identifier, int masterId, int myId, long startTimestamp,
                long latestCommittedTxWhenTxStarted ) throws IOException
        {
            logEntryWriter.writeLogEntry( new LogEntry.Start( xid, identifier, masterId, myId, -1,
                    startTimestamp, latestCommittedTxWhenTxStarted ), buffer );
        }

        @Override
        public void writeCommand( int identifier, XaCommand command ) throws IOException
        {
            logEntryWriter.writeLogEntry( new LogEntry.Command( identifier, command ), buffer );
        }

        @Override
        public void writePrepare( int identifier, long prepareTimestamp ) throws IOException
        {
            logEntryWriter.writeLogEntry( new LogEntry.Prepare( identifier, prepareTimestamp ), buffer );
        }

        @Override
        public void writeCommit( int identifier, boolean twoPhase, long txId, long commitTimestamp ) throws IOException
        {
            LogEntry.Commit commit;
            if ( twoPhase )
            {
                commit = new LogEntry.TwoPhaseCommit( identifier, txId, commitTimestamp );
            }
            else
            {
                commit = new LogEntry.OnePhaseCommit( identifier, txId, commitTimestamp );
            }
            logEntryWriter.writeLogEntry( commit, buffer );
        }

        @Override
        public void writeDone( int identifier ) throws IOException
        {
            logEntryWriter.writeLogEntry( new LogEntry.Done( identifier ), buffer );
        }
    }

    public static class CommandCollector implements Output
    {
        private final List<LogEntry> target;

        public CommandCollector( List<LogEntry> target )
        {
            this.target = target;
        }

        @Override
        public void writeStart( Xid xid, int identifier, int masterId, int myId, long startTimestamp,
                long latestCommittedTxWhenTxStarted ) throws IOException
        {
            add( new LogEntry.Start( xid, identifier, (byte) 0, masterId, myId, 16, startTimestamp,
                    latestCommittedTxWhenTxStarted ) );
        }

        @Override
        public void writeCommand( int identifier, XaCommand command ) throws IOException
        {
            add( new LogEntry.Command( identifier, (byte) 0, command ) );
        }

        @Override
        public void writePrepare( int identifier, long prepareTimestamp ) throws IOException
        {
            add( new LogEntry.Prepare( identifier, (byte) 0, prepareTimestamp ) );
        }

        @Override
        public void writeCommit( int identifier, boolean twoPhase, long txId, long commitTimestamp ) throws IOException
        {
            add( twoPhase ?
                    new LogEntry.TwoPhaseCommit( identifier, (byte) 0, txId, commitTimestamp ) :
                    new LogEntry.OnePhaseCommit( identifier, (byte) 0, txId, commitTimestamp ) );
        }

        @Override
        public void writeDone( int identifier ) throws IOException
        {
            add( new LogEntry.Done( identifier, (byte) 0 ) );
        }

        private void add( LogEntry entry )
        {
            target.add( entry );
        }
    }
}
