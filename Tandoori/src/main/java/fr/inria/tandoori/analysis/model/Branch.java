package fr.inria.tandoori.analysis.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Represents a branch in a Git repository.
 */
public class Branch {
    private final List<Commit> commits;
    private final List<Commit> merges;
    private Commit parentCommit;
    private Commit mergedInto;
    private final int ordinal;

    /**
     * Create the new current branch from its mother,
     * setting if it's either the master branch and the branch ordinal.
     *
     * @param mother  The {@link Branch}'s mother (if null, the new branch will be set as master).
     * @param ordinal The new {@link Branch}h ordinal, i.e. the counter of seen branches.
     * @return A new {@link Branch}.
     */
    public static Branch fromMother(Branch mother, int ordinal) {
        Commit mergedInto = null;
        if (mother != null) {
            mergedInto = mother.getMergedInto();
        }
        return new Branch(ordinal, mergedInto);
    }

    /**
     * Embed all previously seen commits in the new mother branch to avoid infinite loops.
     *
     * @param mother  The mother {@link Branch}, may be null.
     * @param current The current {@link Branch}, can't be null.
     * @return A new mother {@link Branch} with an ordinal set to -1, isMaster to false
     * and the commits of the two branches given in parameters.
     */
    public static Branch newMother(Branch mother, Branch current) {
        Branch branch = new Branch();
        if (mother != null) {
            branch.addCommits(mother.getCommits());
        }
        branch.addCommits(current.getCommits());
        return branch;
    }

    public Branch() {
        this(-1, null);
    }

    public Branch(int ordinal, Commit mergedInto) {
        this.commits = new ArrayList<>();
        this.merges = new ArrayList<>();
        this.ordinal = ordinal;
        this.mergedInto = mergedInto;
        this.parentCommit = null;
    }

    /**
     * Add a commit to the current branch.
     *
     * @param commit The commit to add.
     */
    public void addCommit(Commit commit, int ordinal) {
        commit.setBranchOrdinal(ordinal);
        this.commits.add(commit);
    }

    /**
     * Add a collection of commits to the current branch.
     *
     * @param commit The commits to add.
     */
    public void addCommits(Collection<Commit> commit) {
        this.commits.addAll(commit);
    }

    public List<Commit> getCommits() {
        return commits;
    }

    public List<Commit> getMerges() {
        return merges;
    }

    public void addMerge(Commit commit) {
        merges.add(commit);
    }

    /**
     * Specify if the branch is the repository's principal branch.
     *
     * @return True if the branch is the master branch (principal), false otherwise.
     */
    public boolean isMaster() {
        return parentCommit == null;
    }

    public Commit getParentCommit() {
        return parentCommit;
    }

    public void setParentCommit(Commit parentCommit) {
        this.parentCommit = parentCommit;
    }

    public Commit getMergedInto() {
        return mergedInto;
    }

    public void setMergedInto(Commit mergedInto) {
        this.mergedInto = mergedInto;
    }

    /**
     * Determine if the branch contains the given commit.
     *
     * @param commit The commit to check.
     * @return True if is contained, false otherwise.
     */
    public boolean contains(Commit commit) {
        return commits.contains(commit);
    }

    /**
     * Return the number identifier of the branch for the project.
     *
     * @return A number unique for each project.
     */
    public int getOrdinal() {
        return ordinal;
    }
}
