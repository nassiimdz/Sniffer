package fr.inria.tandoori.analysis.query;

import fr.inria.tandoori.analysis.persistence.Persistence;
import neo4j.HashMapUsageQuery;
import neo4j.InitOnDrawQuery;
import neo4j.InvalidateWithoutRectQuery;
import neo4j.LICQuery;
import neo4j.MIMQuery;
import neo4j.NLMRQuery;
import neo4j.OverdrawQuery;
import neo4j.QueryEngine;
import neo4j.UnsuitedLRUCacheSizeQuery;
import neo4j.UnsupportedHardwareAccelerationQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Retrieve all the smells of a given project for each commits, through Paprika.
 */
public class SmellQuery implements Query {
    private static final Logger logger = LoggerFactory.getLogger(SmellQuery.class.getName());
    private final String db;
    private final Persistence persistence;
    private final int projectId;
    private final SmellDuplicationChecker duplicationChecker;
    private final List<Smell> previousCommitSmells;
    private final List<Smell> currentCommitSmells;
    private final List<Smell> currentCommitRenamed;
    private String currentSha;

    public SmellQuery(int projectId, String db, Persistence persistence) {
        this.projectId = projectId;
        this.db = db;
        this.persistence = persistence;
        duplicationChecker = new SmellDuplicationChecker(projectId, persistence);
        previousCommitSmells = new ArrayList<>();
        currentCommitSmells = new ArrayList<>();
        currentCommitRenamed = new ArrayList<>();
        currentSha = "";
    }

    private List<neo4j.Query> queries(QueryEngine queryEngine) {
        ArrayList<neo4j.Query> queries = new ArrayList<>();
        queries.add(MIMQuery.createMIMQuery(queryEngine));
        queries.add(LICQuery.createLICQuery(queryEngine));
        queries.add(NLMRQuery.createNLMRQuery(queryEngine));
        queries.add(OverdrawQuery.createOverdrawQuery(queryEngine));
        queries.add(UnsuitedLRUCacheSizeQuery.createUnsuitedLRUCacheSizeQuery(queryEngine));
        queries.add(InitOnDrawQuery.createInitOnDrawQuery(queryEngine));
        queries.add(UnsupportedHardwareAccelerationQuery.createUnsupportedHardwareAccelerationQuery(queryEngine));
        queries.add(HashMapUsageQuery.createHashMapUsageQuery(queryEngine));
        queries.add(InvalidateWithoutRectQuery.createInvalidateWithoutRectQuery(queryEngine));
        return queries;
    }

    @Override
    public void query() {
        boolean showDetails = true;
        QueryEngine queryEngine = new QueryEngine(db);

        for (neo4j.Query query : queries(queryEngine)) {
            logger.info("Querying Smells of type: " + query.getSmellName());
            List<Map<String, Object>> result = query.fetchResult(showDetails);

            logger.trace("Got result: " + result);
            writeResults(result, query.getSmellName());
        }
    }

    private void writeResults(List<Map<String, Object>> results, String smellName) {
        Smell currentSmell;
        for (Map<String, Object> instance : results) {
            // We keep track of the smells present in our commit.
            currentSmell = Smell.fromInstance(instance, smellName);
            if (!currentSha.equals(currentSmell.commitSha)) {
                //TODO add sort by app_key in paprika
                changeCurrentCommit(currentSmell.commitSha);
            }
            currentCommitSmells.add(currentSmell);

            Smell original = duplicationChecker.original(currentSmell);
            // If we correctly guessed the smell identifier, we will find it in the previous commit smells
            if (original != null && previousCommitSmells.contains(original)) {
                currentCommitRenamed.add(currentSmell);
                currentSmell.parentInstance = original.instance;
            }
            if (!previousCommitSmells.contains(currentSmell)) {
                insertSmellInstance(currentSmell);
            }
            insertSmellPresence(currentSmell);

            persistence.commit();
        }
    }

    private void insertSmellInstance(Smell smell) {
        String parentQuery = smell.parentInstance != null ? "(" + smellIdQuery(smell.parentInstance, smell.type) + ")" : null;
        String smellInsert = "INSERT INTO Smell (projectId, instance, type, file, renamedFrom) VALUES" +
                "(" + projectId + ", '" + smell.instance + "', '" + smell.type + "', " + smell.file + "', " + parentQuery + ");";
        persistence.addStatements(smellInsert);
    }

    private void insertSmellPresence(Smell smell) {
        insertSmellInCategory(smell, "SmellPresence");
    }

    private void insertSmellInCategory(Smell smell, String category) {
        String smellQuery = smellIdQuery(smell.instance, smell.type);
        String commitQuery = commitIdQuery(smell);
        String smellPresenceInsert = "INSERT INTO " + category + " (smellId, commitId) VALUES " +
                "((" + smellQuery + "), (" + commitQuery + "));";
        persistence.addStatements(smellPresenceInsert);

    }

    private String commitIdQuery(Smell smell) {
        return "SELECT id FROM CommitEntry WHERE sha1 = '" + smell.commitSha + "' AND projectId = " + this.projectId;
    }

    private String smellIdQuery(String instance, String type) {
        return "SELECT id FROM Smell WHERE instance = '" + instance + "' AND type = '" + type + "'";
    }

    /**
     * Transfer all smells from current commit to the previous one, and change the current sha.
     *
     * @param commitSha The new sha.
     */
    private void changeCurrentCommit(String commitSha) {
        insertSmellIntroductions();
        insertSmellRefactoring();

        currentSha = commitSha;
        currentCommitRenamed.clear();
        previousCommitSmells.clear();
        previousCommitSmells.addAll(currentCommitSmells);
        currentCommitSmells.clear();
    }

    private void insertSmellIntroductions() {
        List<Smell> introduction = new ArrayList<>(currentCommitSmells);
        introduction.removeAll(previousCommitSmells);

        for (Smell smell : introduction) {
            if (!currentCommitRenamed.contains(smell)) {
                insertSmellInCategory(smell, "SmellIntroduction");
            }
        }
    }

    private void insertSmellRefactoring() {
        List<Smell> refactoring = new ArrayList<>(previousCommitSmells);
        refactoring.removeAll(currentCommitSmells);

        for (Smell smell : refactoring) {
            if (!currentCommitRenamed.contains(smell)) {
                insertSmellInCategory(smell, "SmellRefactoring");
            }
        }
    }
}
