package fr.inria.tandoori.analysis;

import com.mchange.v2.c3p0.ComboPooledDataSource;
import com.mchange.v2.c3p0.DataSources;
import com.mchange.v2.c3p0.PooledDataSource;
import fr.inria.tandoori.analysis.persistence.PostgresqlPersistence;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.beans.PropertyVetoException;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static fr.inria.tandoori.analysis.Main.DATABASE_PASSWORD;
import static fr.inria.tandoori.analysis.Main.DATABASE_URL;
import static fr.inria.tandoori.analysis.Main.DATABASE_USERNAME;

/**
 * Class handling a single app analysis process in Tandoori.
 */
public class MultiAppAnalysis {
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(MultiAppAnalysis.class.getName());

    private final List<String> applications;
    private final Map<String, String> remoteRepositories;
    private final String paprikaDBs;
    private final String githubToken;
    private final int threadsCount;
    private final String appLocalRepositories;
    private final DataSource connectionPool;

    /**
     * Start a simultaneous analysis on multiple projects.
     *
     * @param appsFile             CSV file containing the app names and Github remoteRepositories.
     * @param paprikaDBs           Path to the Paprika databases under the form paprika_db/$appName.
     * @param githubToken          Github API token to query on developers.
     * @param threadsCount         Number of available threads for the analysis.
     * @param appLocalRepositories Path to the git remoteRepositories of applications to avoid cloning them, under the form repos/$appName.
     */
    MultiAppAnalysis(String appsFile, String paprikaDBs, String githubToken, int threadsCount, String appLocalRepositories) {
        this.paprikaDBs = paprikaDBs;
        this.githubToken = githubToken;
        this.threadsCount = threadsCount;
        this.appLocalRepositories = appLocalRepositories;

        applications = new ArrayList<>();
        remoteRepositories = new HashMap<>();
        parseAppsCSV(Paths.get(appsFile));
        connectionPool = initializeConnectionPool();
    }

    private void parseAppsCSV(Path appsFile) {
        try (BufferedReader br = new BufferedReader(new FileReader(appsFile.toFile()))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] appEntry = line.split(",");
                String appName = appEntry[0];
                if (appName.isEmpty()) {
                    continue;
                }

                applications.add(appName);
                if (appEntry.length > 1) {
                    remoteRepositories.put(appName, appEntry[1]);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to load applications CSV: " + appsFile, e);
        }

    }

    private DataSource initializeConnectionPool() {
        DataSource ds_unpooled = null;
        try {

            ds_unpooled = DataSources.unpooledDataSource("jdbc:postgresql:" + DATABASE_URL,
                    DATABASE_USERNAME, DATABASE_PASSWORD);
            return DataSources.pooledDataSource(ds_unpooled);
        } catch (SQLException e) {
            throw new RuntimeException("Unable to create DataSource", e);
        }
    }

    public void analyze() throws InterruptedException {
        logger.info("Starting multi application analysis using " + threadsCount + " threads");
        ExecutorService executorService = Executors.newFixedThreadPool(threadsCount);
        String repository;
        String paprikaDB;

        SingleAnalysisTask analysis;
        List<SingleAnalysisTask> tasks = new ArrayList<>();
        for (String app : applications) {

            Connection connection = null;
            try {
                connection = connectionPool.getConnection();
            } catch (SQLException e) {
                logger.error("Unable to open connection to database", e);
                continue;
            }

            repository = chooseRepository(app);
            paprikaDB = Paths.get(paprikaDBs, app, "databases", "graph.db").toString();
            analysis = new SingleAnalysisTask(app, repository, paprikaDB, githubToken, connection);
            logger.info("New app analysis: " + analysis);
            tasks.add(analysis);
        }

        executorService.invokeAll(tasks);
        executorService.shutdown();
        executorService.awaitTermination(24, TimeUnit.HOURS);
    }

    private String chooseRepository(String app) {
        if (appLocalRepositories != null) {
            return Paths.get(appLocalRepositories, app).toString();
        } else {
            return remoteRepositories.get(app);
        }
    }

    private static final class SingleAnalysisTask implements Callable<Void> {
        private String application;
        private String repository;
        private String paprikaDB;
        private String githubToken;
        private Connection connection;

        public SingleAnalysisTask(String application, String repository, String paprikaDB, String githubToken, Connection connection) {
            this.application = application;
            this.repository = repository;
            this.paprikaDB = paprikaDB;
            this.githubToken = githubToken;
            this.connection = connection;
        }

        @Override
        public Void call() throws Exception {
            new SingleAppAnalysis(application, repository, paprikaDB, githubToken, new PostgresqlPersistence(connection)).analyze();
            return null;
        }

        @Override
        public String toString() {
            return "SingleAnalysisTask{" +
                    "application='" + application + '\'' +
                    ", repository='" + repository + '\'' +
                    ", paprikaDB='" + paprikaDB + '\'' +
                    ", githubToken='" + (githubToken == null ? null : "XXXX (is set)") + '\'' +
                    '}';
        }
    }

    /**
     * Constructor for command line arguments
     *
     * @param arguments The command line arguments.
     */
    MultiAppAnalysis(Namespace arguments) {
        this(
                arguments.getString("apps"),
                arguments.getString("databases"),
                arguments.getString("githubToken"),
                arguments.getInt("threads"),
                arguments.getString("repositories")
        );
    }

    /**
     * Defines the available inputs for a single app analysis.
     *
     * @param parser The parser to configure
     */
    static void setArguments(Subparser parser) {

        parser.addArgument("-a", "--apps")
                .help("CSV containing the list of applications to analyze")
                .type(String.class)
                .required(true);

        parser.addArgument("-db", "--databases")
                .help("Path to the Paprika databases under the form paprika_db/$appName")
                .type(String.class)
                .required(true);

        parser.addArgument("-k", "--githubToken")
                .help("Paprika analysis database")
                .type(String.class)
                .required(false);

        parser.addArgument("-t", "--threads")
                .help("Number of threads to allocate")
                .type(Integer.class)
                .setDefault(1)
                .required(false);

        parser.addArgument("-r", "--repositories")
                .help("Local directory containing repositories: $repo/$appName/.git")
                .type(String.class)
                .required(false);

    }
}