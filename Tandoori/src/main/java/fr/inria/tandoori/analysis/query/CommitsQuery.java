package fr.inria.tandoori.analysis.query;

import fr.inria.tandoori.analysis.persistence.Persistence;
import fr.inria.tandoori.analysis.tools.CommitDetails;
import fr.inria.tandoori.analysis.tools.GitDiff;
import fr.inria.tandoori.analysis.tools.GitRename;
import fr.inria.tandoori.analysis.tools.Repository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Fetch all commits and developers for a project.
 */
public class CommitsQuery implements Query {
    private static final Logger logger = LoggerFactory.getLogger(CommitsQuery.class.getName());
    private int projectId;
    private final Repository repository;
    private Persistence persistence;

    public CommitsQuery(int projectId, String repository, Persistence persistence) {
        this.projectId = projectId;
        this.repository = new Repository(repository);
        this.persistence = persistence;
    }

    @Override
    public void query() throws QueryException {
        logger.info("### Starting Commits insertion ###");
        Git gitRepository;
        try {
            gitRepository = repository.initializeRepository();
        } catch (Repository.RepositoryException e) {
            throw new QueryException(logger.getName(), e);
        }
        Iterable<RevCommit> commits = getCommits(gitRepository);
        List<RevCommit> commitsList = new ArrayList<>();
        // Reverse our commit list.
        for (RevCommit commit : commits) {
            commitsList.add(0, commit);
        }

        String[] commitStatements = new String[commitsList.size()];
        List<String> authorStatements = new ArrayList<>();
        List<String> renameStatements = new ArrayList<>();
        int commitCount = 0;
        CommitDetails details;
        for (RevCommit commit : commitsList) {
            logger.debug("=> Analyzing commit: " + commit.name());
            details = CommitDetails.fetch(repository.getRepoDir().toString(), commit.name());

            authorStatements.addAll(authorStatements(commit.getAuthorIdent().getEmailAddress()));
            commitStatements[commitCount] = commitStatement(commit, commitCount++, details);
            renameStatements.addAll(fileRenameStatements(commit, details));
        }

        // We add everything in a bulk insert since we must have a coherent state.
        // Warning, we have to insert authors, then commits, then renaming!
        persistence.addStatements(authorStatements.toArray(new String[0]));
        persistence.addStatements(commitStatements);
        persistence.addStatements(renameStatements.toArray(new String[0]));
        persistence.commit();

        repository.finalizeRepository();
    }

    private static Iterable<RevCommit> getCommits(Git gitRepo) throws QueryException {
        Iterable<RevCommit> commits;
        try {
            commits = gitRepo.log().call();
        } catch (GitAPIException e) {
            throw new QueryException(logger.getName(), e);
        }
        return commits;
    }

    private List<String> authorStatements(String emailAddress) {
        String developerQuery = persistence.developerQueryStatement(projectId, emailAddress);
        List<String> statements = new ArrayList<>();

        // Try to insert the developer if not exist
        String authorInsert = "INSERT INTO Developer (username) VALUES ('" + emailAddress + "') ON CONFLICT DO NOTHING;";
        statements.add(authorInsert);

        // Try to insert the developer/project mapping if not exist
        String authorProjectInsert = "INSERT INTO ProjectDeveloper (developerId, projectId) VALUES (" +
                "(" + developerQuery + "), " + projectId + ") ON CONFLICT DO NOTHING;";
        statements.add(authorProjectInsert);

        return statements;
    }

    private String commitStatement(RevCommit commit, int count, CommitDetails details) {
        String authorEmail = commit.getAuthorIdent().getEmailAddress();
        String developerQuery = persistence.developerQueryStatement(projectId, authorEmail);
        GitDiff diff = details.diff;

        DateTime commitDate = new DateTime(((long) commit.getCommitTime()) * 1000);
        logger.trace("Commit time is: " + commit.getCommitTime() + "(datetime: " + commitDate + ")");

        return "INSERT INTO CommitEntry (projectId, developerId, sha1, ordinal, date, additions, deletions, filesChanged, message) VALUES ('" +
                projectId + "', (" + developerQuery + "), '" + commit.name() + "', " + count + ", '" + commitDate.toString() +
                "', " + diff.getAddition() + ", " + diff.getDeletion() + ", " + diff.getChangedFiles() + ", $$" + commit.getFullMessage() + "$$) ON CONFLICT DO NOTHING";
    }

    private List<String> fileRenameStatements(RevCommit commit, CommitDetails details) {
        String commitSelect = persistence.commitQueryStatement(projectId, commit.name());
        List<String> result = new ArrayList<>();

        for (GitRename rename : details.renames) {
            if (!(rename.oldFile.endsWith(".java") && rename.newFile.endsWith(".java"))) {
                continue;
            }

            logger.debug("  => Found .java renamed: " + rename.oldFile);
            logger.trace("    => new file: " + rename.newFile);
            logger.trace("    => Similarity: " + rename.similarity);

            result.add(renameInsertStatement(commitSelect, rename));
        }
        return result;
    }

    /**
     * Generate an insertion line for the given rename statement.
     *
     * @param commitSelect Query to select the right commit id.
     * @param rename       Rename info to use.
     */
    private String renameInsertStatement(String commitSelect, GitRename rename) {
        return "INSERT INTO FileRename (projectId, commitId, oldFile, newFile, similarity) VALUES ('" +
                projectId + "',(" + commitSelect + "), '" + rename.oldFile + "', '" +
                rename.newFile + "', " + rename.similarity + ") ON CONFLICT DO NOTHING";
    }
}
