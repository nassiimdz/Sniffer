package fr.inria.tandoori.analysis.query;

import fr.inria.tandoori.analysis.persistence.Persistence;
import fr.inria.tandoori.analysis.persistence.queries.CommitQueries;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;

/**
 * Abstract query providing some helper methods.
 */
public abstract class PersistenceAnalyzer {
    protected final Logger logger;
    protected final int projectId;
    protected final Persistence persistence;
    protected final CommitQueries commitQueries;

    protected PersistenceAnalyzer(Logger logger, int projectId, Persistence persistence, CommitQueries commitQueries) {
        this.logger = logger;
        this.projectId = projectId;
        this.persistence = persistence;
        this.commitQueries = commitQueries;
    }


    /**
     * Retrieve the sha1 of the last commit analyzed by Paprika.
     *
     * @return The current Paprika HEAD sha1.
     * @throws QueryException If we could not find the last paprika Commit, this should not happen.
     */
    protected String fetchLastProjectCommitSha() throws QueryException {
        List<Map<String, Object>> result = persistence.query(commitQueries.lastProjectCommitShaQuery(projectId));
        if (result.isEmpty()) {
            throw new QueryException(logger.getName(), "Unable to fetch last commit for project: " + projectId);
        }
        return (String) result.get(0).get("sha1");
    }

    /**
     * Tells if the commit exists in the Paprika analysis dataset.
     *
     * @param commitSha The sha1 to check for existence.
     * @return True if the commit is present in the CommitEntry table, False otherwise.
     */
    protected boolean paprikaHasCommit(String commitSha) {
        String commitQuery = commitQueries.idFromShaQuery(this.projectId, commitSha);
        List<Map<String, Object>> result = persistence.query(commitQuery);
        if (logger.isTraceEnabled()) {
            if (!result.isEmpty()) {
                String sha1 = String.valueOf(result.get(0).get("id"));
                logger.trace("[" + projectId + "]  => commit id: " + sha1);
            } else {
                logger.trace("[" + projectId + "] NO FOUND COMMIT!");
            }
        }
        return !result.isEmpty();
    }
}
