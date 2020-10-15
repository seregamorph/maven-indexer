package org.apache.maven.indexer.examples;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.Bits;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.ArtifactInfoFilter;
import org.apache.maven.index.ArtifactInfoGroup;
import org.apache.maven.index.Field;
import org.apache.maven.index.FlatSearchRequest;
import org.apache.maven.index.FlatSearchResponse;
import org.apache.maven.index.GroupedSearchRequest;
import org.apache.maven.index.GroupedSearchResponse;
import org.apache.maven.index.Grouping;
import org.apache.maven.index.Indexer;
import org.apache.maven.index.IteratorSearchRequest;
import org.apache.maven.index.IteratorSearchResponse;
import org.apache.maven.index.MAVEN;
import org.apache.maven.index.context.IndexCreator;
import org.apache.maven.index.context.IndexUtils;
import org.apache.maven.index.context.IndexingContext;
import org.apache.maven.index.expr.SourcedSearchExpression;
import org.apache.maven.index.expr.UserInputSearchExpression;
import org.apache.maven.index.search.grouping.GAGrouping;
import org.apache.maven.index.updater.IndexUpdateRequest;
import org.apache.maven.index.updater.IndexUpdateResult;
import org.apache.maven.index.updater.IndexUpdater;
import org.apache.maven.index.updater.ResourceFetcher;
import org.apache.maven.index.updater.WagonHelper;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.events.TransferListener;
import org.apache.maven.wagon.observers.AbstractTransferListener;
import org.codehaus.plexus.DefaultContainerConfiguration;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.InvalidVersionSpecificationException;
import org.eclipse.aether.version.Version;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.SmartDataSource;

/**
 * Collection of some use cases.
 */
public class BasicUsageExample
{
    public static void main( String[] args )
        throws Exception
    {
        final BasicUsageExample basicUsageExample = new BasicUsageExample();
        basicUsageExample.perform(args);
    }

    // ==

    private final PlexusContainer plexusContainer;

    private final Indexer indexer;

    private final IndexUpdater indexUpdater;

    private final Wagon httpWagon;

    private IndexingContext centralContext;

    public BasicUsageExample()
        throws PlexusContainerException, ComponentLookupException
    {
        // here we create Plexus container, the Maven default IoC container
        // Plexus falls outside of MI scope, just accept the fact that
        // MI is a Plexus component ;)
        // If needed more info, ask on Maven Users list or Plexus Users list
        // google is your friend!
        final DefaultContainerConfiguration config = new DefaultContainerConfiguration();
        config.setClassPathScanning( PlexusConstants.SCANNING_INDEX );
        this.plexusContainer = new DefaultPlexusContainer( config );

        // lookup the indexer components from plexus
        this.indexer = plexusContainer.lookup( Indexer.class );
        this.indexUpdater = plexusContainer.lookup( IndexUpdater.class );
        // lookup wagon used to remotely fetch index
        this.httpWagon = plexusContainer.lookup( Wagon.class, "http" );

    }

    public void perform(String[] args)
            throws IOException, ComponentLookupException, InvalidVersionSpecificationException, SQLException {
        // Files where local cache is (if any) and Lucene Index should be located
        File centralLocalCache = new File( "data/central-cache" );
        File centralIndexDir = new File( "data/central-index" );

        // Creators we want to use (search for fields it defines)
        List<IndexCreator> indexers = new ArrayList<>();
        indexers.add( plexusContainer.lookup( IndexCreator.class, "min" ) );
        indexers.add( plexusContainer.lookup( IndexCreator.class, "jarContent" ) );
        indexers.add( plexusContainer.lookup( IndexCreator.class, "maven-plugin" ) );

        // Create context for central repository index
        centralContext =
            indexer.createIndexingContext( "central-context", "central", centralLocalCache, centralIndexDir,
                                           "https://repo1.maven.org/maven2", null, true, true, indexers );

        // Update the index (incremental update will happen if this is not 1st run and files are not deleted)
        // This whole block below should not be executed on every app start, but rather controlled by some configuration
        // since this block will always emit at least one HTTP GET. Central indexes are updated once a week, but
        // other index sources might have different index publishing frequency.
        // Preferred frequency is once a week.
        if (Arrays.asList(args).contains("-f"))
        {
            System.out.println( "Updating Index..." );
            System.out.println( "This might take a while on first run, so please be patient!" );
            // Create ResourceFetcher implementation to be used with IndexUpdateRequest
            // Here, we use Wagon based one as shorthand, but all we need is a ResourceFetcher implementation
            final AtomicLong downloaded = new AtomicLong(0);
            final AtomicLong nextReport = new AtomicLong(0);
            TransferListener listener = new AbstractTransferListener()
            {
                @Override
                public void transferStarted(TransferEvent transferEvent )
                {
                    System.out.print( "  Downloading " + transferEvent.getResource().getName() );
                }

                @Override
                public void transferProgress(TransferEvent transferEvent, byte[] buffer, int length )
                {
                    long total = downloaded.addAndGet(length);
                    if (total > nextReport.get()) {
                        System.out.println(nextReport.get() + " bytes");
                        nextReport.addAndGet(10_000_000);
                    }
                }

                @Override
                public void transferCompleted(TransferEvent transferEvent )
                {
                    System.out.println( " - Done" + " Local file " + transferEvent.getLocalFile());
                }
            };
            ResourceFetcher resourceFetcher = new WagonHelper.WagonFetcher( httpWagon, listener, null, null );

            Date centralContextCurrentTimestamp = centralContext.getTimestamp();
            IndexUpdateRequest updateRequest = new IndexUpdateRequest( centralContext, resourceFetcher );
            IndexUpdateResult updateResult = indexUpdater.fetchAndUpdateIndex( updateRequest );
            if ( updateResult.isFullUpdate() )
            {
                System.out.println( "Full update happened!" );
            }
            else if ( updateResult.getTimestamp().equals( centralContextCurrentTimestamp ) )
            {
                System.out.println( "No update needed, index is up to date!" );
            }
            else
            {
                System.out.println(
                    "Incremental update happened, change covered " + centralContextCurrentTimestamp + " - "
                        + updateResult.getTimestamp() + " period." );
            }

            System.out.println();
        }

        System.out.println();
        System.out.println( "Using index" );
        System.out.println( "===========" );
        System.out.println();

        // ====
        // Case:
        // dump all the GAVs
        // NOTE: will not actually execute do this below, is too long to do (Central is HUGE), but is here as code
        // example
        int counter = 0;
        int nullCounter = 0;
        if (Arrays.asList(args).contains("-e"))
        {
            final IndexSearcher searcher = centralContext.acquireIndexSearcher();
            try (Connection conn = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/indexer", "indexer", "indexer")) {
            //try (FileWriter fw = new FileWriter("all-artifacts1.txt")) {
                //PrintWriter pw = new PrintWriter(fw);
                conn.setAutoCommit(false);
                JdbcTemplate jdbc = jdbc(conn);
                List<Object[]> batchArgs = new ArrayList<>();
                final IndexReader ir = searcher.getIndexReader();
                Bits liveDocs = MultiFields.getLiveDocs( ir );

                String sql =
                        "INSERT INTO gav("
                                + "group_id, "
                                + "artifact_id, "
                                + "version, "
                                + "classifier, "
                                + "file_extension, "
                                + "artifact_version, "
                                + "last_modified, "
                                + "sha1, "
                                + "sources_exists, "
                                + "javadoc_exists, "
                                + "signature_exists, "
                                + "size, "
                                + "packaging, "
                                + "name, "
                                + "description"
                                + ") VALUES ("
                                + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?"
                                + ")"
                        ;

                exit: for ( int i = 0; i < ir.maxDoc(); i++ )
                {
                    if ( liveDocs == null || liveDocs.get( i ) )
                    {
                        final Document doc = ir.document( i );
                        final ArtifactInfo ai = IndexUtils.constructArtifactInfo( doc, centralContext );
                        if (ai == null) {
                            IndexUtils.constructArtifactInfo( doc, centralContext );
                            nullCounter++;
                        } else {
                            //
//                            if ("hamcrest-more-matchers".equals(ai.getArtifactId())) {
//                                System.out.println(ai);
//                            }
                            //String id = ai.getGroupId() + ":" + ai.getArtifactId() + ":" + ai.getVersion()
                            //        + (ai.getClassifier() == null ? "" : ":" + ai.getClassifier());
                            //pw.println(id);
                            try {
                                Object batch[] = {
                                        ai.getGroupId(),
                                        ai.getArtifactId(),
                                        ai.getVersion(),
                                        Objects.toString(ai.getClassifier(), ""),
                                        Objects.toString(ai.getFileExtension(), ""),
                                        Objects.toString(ai.getArtifactVersion(), null),
                                        new Timestamp(ai.getLastModified()),
                                        ai.getSha1(),
                                        Integer.parseInt(ai.getSourcesExists().toString()),
                                        Integer.parseInt(ai.getJavadocExists().toString()),
                                        Integer.parseInt(ai.getSignatureExists().toString()),
                                        ai.getSize(),
                                        ai.getPackaging(),
                                        ai.getName(),
                                        maxLength(ai.getDescription(), 16384)
                                };
                                batchArgs.add(batch);

                                if (++counter % 500 == 0) {
                                    jdbc.batchUpdate(sql, batchArgs);
                                    batchArgs.clear();
                                    System.out.println("counter = " + counter);
                                    conn.commit();
                                }
                            } catch (DataIntegrityViolationException e) {
                                e.printStackTrace();
                                System.out.println(ai);
                                return;
                            }
                        }
                    }
                }
                //pw.flush();
                jdbc.batchUpdate(sql, batchArgs);
                conn.commit();
            }
            finally
            {
                centralContext.releaseIndexSearcher( searcher );
            }
        }

        System.out.println("nulls: " + nullCounter);
        System.out.println("total counter: " + counter);

        if (true) return;

        // ====
        // Case:
        // Search for all GAVs with known G and A and having version greater than V

        final GenericVersionScheme versionScheme = new GenericVersionScheme();
        final String versionString = "1.5.0";
        final Version version = versionScheme.parseVersion( versionString );

        // construct the query for known GA
        final Query groupIdQ =
            indexer.constructQuery( MAVEN.GROUP_ID, new SourcedSearchExpression( "org.sonatype.nexus" ) );
        final Query artifactIdQ =
            indexer.constructQuery( MAVEN.ARTIFACT_ID, new SourcedSearchExpression( "nexus-api" ) );

        final BooleanQuery query = new BooleanQuery.Builder()
            .add( groupIdQ, Occur.MUST )
            .add( artifactIdQ, Occur.MUST )
            // we want "jar" artifacts only
            .add( indexer.constructQuery( MAVEN.PACKAGING, new SourcedSearchExpression( "jar" ) ), Occur.MUST )
            // we want main artifacts only (no classifier)
            // Note: this below is unfinished API, needs fixing
            .add( indexer.constructQuery( MAVEN.CLASSIFIER,
                    new SourcedSearchExpression( Field.NOT_PRESENT ) ), Occur.MUST_NOT )
            .build();

        // construct the filter to express "V greater than"
        final ArtifactInfoFilter versionFilter = new ArtifactInfoFilter()
        {
            @Override
            public boolean accepts(final IndexingContext ctx, final ArtifactInfo ai )
            {
                try
                {
                    final Version aiV = versionScheme.parseVersion( ai.getVersion() );
                    // Use ">=" if you are INCLUSIVE
                    return aiV.compareTo( version ) > 0;
                }
                catch ( InvalidVersionSpecificationException e )
                {
                    // do something here? be safe and include?
                    return true;
                }
            }
        };

        System.out.println(
            "Searching for all GAVs with G=org.sonatype.nexus and nexus-api and having V greater than 1.5.0" );
        final IteratorSearchRequest request =
            new IteratorSearchRequest( query, Collections.singletonList( centralContext ), versionFilter );
        final IteratorSearchResponse response = indexer.searchIterator( request );
        for ( ArtifactInfo ai : response )
        {
            System.out.println( ai.toString() );
        }

        // Case:
        // Use index
        // Searching for some artifact
        Query gidQ =
            indexer.constructQuery( MAVEN.GROUP_ID, new SourcedSearchExpression( "org.apache.maven.indexer" ) );
        Query aidQ = indexer.constructQuery( MAVEN.ARTIFACT_ID, new SourcedSearchExpression( "indexer-artifact" ) );

        BooleanQuery bq = new BooleanQuery.Builder()
                .add( gidQ, Occur.MUST )
                .add( aidQ, Occur.MUST )
                .build();

        searchAndDump( indexer, "all artifacts under GA org.apache.maven.indexer:indexer-artifact", bq );

        // Searching for some main artifact
        bq = new BooleanQuery.Builder()
                .add( gidQ, Occur.MUST )
                .add( aidQ, Occur.MUST )
//                .add( indexer.constructQuery( MAVEN.CLASSIFIER, new SourcedSearchExpression( "*" ) ), Occur.MUST_NOT )
                .build();

        searchAndDump( indexer, "main artifacts under GA org.apache.maven.indexer:indexer-artifact", bq );

        // doing sha1 search
        searchAndDump( indexer, "SHA1 7ab67e6b20e5332a7fb4fdf2f019aec4275846c2",
                       indexer.constructQuery( MAVEN.SHA1,
                                               new SourcedSearchExpression( "7ab67e6b20e5332a7fb4fdf2f019aec4275846c2" )
                       )
        );

        searchAndDump( indexer, "SHA1 7ab67e6b20 (partial hash)",
                       indexer.constructQuery( MAVEN.SHA1, new UserInputSearchExpression( "7ab67e6b20" ) ) );

        // doing classname search (incomplete classname)
        searchAndDump( indexer, "classname DefaultNexusIndexer (note: Central does not publish classes in the index)",
                       indexer.constructQuery( MAVEN.CLASSNAMES,
                                               new UserInputSearchExpression( "DefaultNexusIndexer" ) ) );

        // doing search for all "canonical" maven plugins latest versions
        bq = new BooleanQuery.Builder()
            .add( indexer.constructQuery( MAVEN.PACKAGING, new SourcedSearchExpression( "maven-plugin" ) ), Occur.MUST )
            .add( indexer.constructQuery( MAVEN.GROUP_ID,
                    new SourcedSearchExpression( "org.apache.maven.plugins" ) ), Occur.MUST )
            .build();

        searchGroupedAndDump( indexer, "all \"canonical\" maven plugins", bq, new GAGrouping() );

        // doing search for all archetypes latest versions
        searchGroupedAndDump( indexer, "all maven archetypes (latest versions)",
                              indexer.constructQuery( MAVEN.PACKAGING,
                                                      new SourcedSearchExpression( "maven-archetype" ) ),
                              new GAGrouping() );

        // close cleanly
        indexer.closeIndexingContext( centralContext, false );
    }

    public void searchAndDump( Indexer nexusIndexer, String descr, Query q )
        throws IOException
    {
        System.out.println( "Searching for " + descr );

        FlatSearchResponse response = nexusIndexer.searchFlat( new FlatSearchRequest( q, centralContext ) );

        for ( ArtifactInfo ai : response.getResults() )
        {
            System.out.println( ai.toString() );
        }

        System.out.println( "------" );
        System.out.println( "Total: " + response.getTotalHitsCount() );
        System.out.println();
    }

    private static final int MAX_WIDTH = 60;

    public void searchGroupedAndDump( Indexer nexusIndexer, String descr, Query q, Grouping g )
        throws IOException
    {
        System.out.println( "Searching for " + descr );

        GroupedSearchResponse response = nexusIndexer.searchGrouped( new GroupedSearchRequest( q, g, centralContext ) );

        for ( Map.Entry<String, ArtifactInfoGroup> entry : response.getResults().entrySet() )
        {
            ArtifactInfo ai = entry.getValue().getArtifactInfos().iterator().next();
            System.out.println( "* Entry " + ai );
            System.out.println( "  Latest version:  " + ai.getVersion() );
            System.out.println( StringUtils.isBlank( ai.getDescription() )
                                    ? "No description in plugin's POM."
                                    : StringUtils.abbreviate( ai.getDescription(), MAX_WIDTH ) );
            System.out.println();
        }

        System.out.println( "------" );
        System.out.println( "Total record hits: " + response.getTotalHitsCount() );
        System.out.println();
    }

    static JdbcTemplate jdbc(Connection connection) {
        SmartDataSource ds = smartDataSource(connection);
        return new JdbcTemplate(ds);
    }

    static SmartDataSource smartDataSource(Connection connection) {
        return new SingleConnectionDataSource(connection, false);
    }

    private static String maxLength(String str, int maxLength) {
        if (str == null || str.length() < maxLength) {
            return str;
        } else {
            return str.substring(0, maxLength);
        }
    }
}
