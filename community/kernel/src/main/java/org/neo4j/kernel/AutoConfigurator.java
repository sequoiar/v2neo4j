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
package org.neo4j.kernel;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.HashMap;
import java.util.Map;

import org.neo4j.kernel.impl.nioneo.store.FileSystemAbstraction;
import org.neo4j.kernel.logging.ConsoleLogger;

/**
 * @deprecated This will be moved to internal packages in the next major release.
 */
@Deprecated
public class AutoConfigurator
{
    public static final int NUMBER_OF_STORES_FOR_MEMORY_MAPPING = 6;

    private final int totalPhysicalMemMb;
    private final int maxVmUsageMb;
    private final File dbPath;
    private final boolean useMemoryMapped;
    private final ConsoleLogger logger;
    private final FileSystemAbstraction fs;

    public AutoConfigurator( FileSystemAbstraction fs, File dbPath, boolean useMemoryMapped, ConsoleLogger logger )
    {
        this( fs, dbPath, useMemoryMapped, physicalMemory(), Runtime.getRuntime().maxMemory(), logger );
    }

    AutoConfigurator( FileSystemAbstraction fs, File dbPath, boolean useMemoryMapped, long physicalMemory, long vmMemory,
                      ConsoleLogger logger )
    {
        if ( physicalMemory == -1 )
        {
            logger.warn( "Could not determine the size of the physical memory. Continuing but without memory mapped buffers." );
        }
        else if ( physicalMemory < vmMemory )
        {
            logger.log( "WARNING! Physical memory("+(physicalMemory/(1024*1000))+"MB) is less than assigned JVM memory("+(vmMemory/(1024*1000))+"MB). Continuing but with available JVM memory set to available physical memory" );
            vmMemory = physicalMemory;
        }

        this.fs = fs;
        this.dbPath = dbPath;
        this.useMemoryMapped = useMemoryMapped;
        this.logger = logger;
        if ( physicalMemory != -1 )
        {
            totalPhysicalMemMb = (int) (physicalMemory / 1024 / 1024);
        }
        else
        {
            totalPhysicalMemMb = -1;
        }
        maxVmUsageMb = (int) (vmMemory / 1024 / 1024);
    }

    static long physicalMemory()
    {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        for ( Class<?> beanClass : osBean.getClass().getInterfaces() )
        {
            try
            {
                return (Long) beanClass.getMethod( "getTotalPhysicalMemorySize" ).invoke( osBean );
            }
            catch ( Exception | LinkageError e )
            { // ok we tried but probably 1.5 JVM or other class library implementation
            }
        }
        return -1;
    }

    public String getNiceMemoryInformation()
    {
        return "Physical mem: " + totalPhysicalMemMb + "MB, Heap size: " + maxVmUsageMb + "MB";
    }

    public Map<String, String> configure()
    {
        Map<String, String> autoConfiguredConfig = new HashMap<String, String>();
        if ( totalPhysicalMemMb > 0 )
        {
            if ( useMemoryMapped )
            {
                int availableMem = (totalPhysicalMemMb - maxVmUsageMb);
                // leave 15% for OS and other progs
                availableMem -= (int) (availableMem * 0.15f);
                assignMemory( autoConfiguredConfig, availableMem );
            }
            else
            {
                // use half of heap (if needed) for buffers
                assignMemory( autoConfiguredConfig, maxVmUsageMb / 2 );
            }
        }
        return autoConfiguredConfig;
    }

    private int calculate( int memLeft, int storeSize, float use, float expand, boolean canExpand )
    {
        int size;
        if ( storeSize > (memLeft * use) )
        {
            size = (int) (memLeft * use);
        }
        else if ( canExpand )
        {
            if ( storeSize * expand * NUMBER_OF_STORES_FOR_MEMORY_MAPPING < memLeft * use )
            {
                size = (int) ( memLeft * use / NUMBER_OF_STORES_FOR_MEMORY_MAPPING );
            }
            else
            {
                size = (int) (memLeft * use);
            }
        }
        else
        {
            size = storeSize;
        }
        return size;
    }

    private void assignMemory( Map<String, String> config, int availableMem )
    {
        int nodeStore = getFileSizeMb( "nodestore.db" );
        int relStore = getFileSizeMb( "relationshipstore.db" );
        int relGroupStore = getFileSizeMb( "relationshipgroupstore.db" );
        int propStore = getFileSizeMb( "propertystore.db" );
        int stringStore = getFileSizeMb( "propertystore.db.strings" );
        int arrayStore = getFileSizeMb( "propertystore.db.arrays" );

        int totalSize = nodeStore + relStore + relGroupStore + propStore + stringStore + arrayStore;
        boolean expand = false;
        if ( totalSize * 1.15f < availableMem )
        {
            expand = true;
        }
        int memLeft = availableMem;
        relStore = calculate( memLeft, relStore, 0.75f, 1.1f, expand );
        memLeft -= relStore;
        nodeStore = calculate( memLeft, nodeStore, 0.2f, 1.1f, expand );
        memLeft -= nodeStore;
        relGroupStore = calculate( memLeft, relGroupStore, 0.0001f, 1.1f, expand ); // around 0.03% size of nodestore
        memLeft -= relGroupStore;
        propStore = calculate( memLeft, propStore, 0.75f, 1.1f, expand );
        memLeft -= propStore;
        stringStore = calculate( memLeft, stringStore, 0.75f, 1.1f, expand );
        memLeft -= stringStore;
        arrayStore = calculate( memLeft, arrayStore, 1.0f, 1.1f, expand );
        memLeft -= arrayStore;

        configPut( config, "nodestore.db", nodeStore );
        configPut( config, "relationshipstore.db", relStore );
        configPut( config, "relationshipgroupstore.db", relGroupStore );
        configPut( config, "propertystore.db", propStore );
        configPut( config, "propertystore.db.strings", stringStore );
        configPut( config, "propertystore.db.arrays", arrayStore );
    }

    private void configPut( Map<String, String> config, String store, int size )
    {
        // Don't overwrite explicit config
        String key = "neostore." + store + ".mapped_memory";
        config.put( key, size + "M" );
    }

    private int getFileSizeMb( String file )
    {
        long length = fs.getFileSize( new File( dbPath, "neostore." + file ) );
        int mb = (int) (length / 1024 / 1024);
        if ( mb > 0 )
        {
            return mb;
        }
        // default return 1MB if small or empty file
        return 1;
    }
}
