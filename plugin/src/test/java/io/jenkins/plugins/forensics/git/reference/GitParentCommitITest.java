package io.jenkins.plugins.forensics.git.reference;

import hudson.model.FreeStyleProject;

import java.io.IOException;

import hudson.plugins.git.GitSCM;
import org.apache.commons.lang.StringUtils;
import org.junit.Test;

import hudson.model.Run;

import io.jenkins.plugins.forensics.git.util.GitITest;
import io.jenkins.plugins.forensics.reference.ReferenceBuild;

import static io.jenkins.plugins.forensics.assertions.Assertions.*;
import static io.jenkins.plugins.forensics.git.assertions.Assertions.*;

public class GitParentCommitITest extends GitITest {

    @Test
    public void testSingleBranch() throws IOException {
        // Init commit
        String currentCommit = getHead();
        FreeStyleProject job = createFreeStyleProject("SingleBranch");

        Run<?, ?> build = buildSuccessfully(job);
        GitCommitsRecord record = build.getAction(GitCommitsRecord.class);
        ReferenceBuild refBuild = build.getAction(ReferenceBuild.class);
        // Init Commit has no parent commit, no reference build
        assertThat(record).isNotNull()
                .hasParentCommit(StringUtils.EMPTY);
        assertThat(refBuild).isNotNull()
                .hasOwner(build)
                .doesNotHaveReferenceBuild();

        writeFileAsAuthorBar("Second Commit in File");

        Run<?, ?> nextBuild = buildSuccessfully(job);
        GitCommitsRecord nextRecord = nextBuild.getAction(GitCommitsRecord.class);
        ReferenceBuild nextRefBuild = nextBuild.getAction(ReferenceBuild.class);
        assertThat(nextRecord).isNotNull()
                .hasParentCommit(currentCommit);
        assertThat(nextRefBuild).isNotNull()
                .hasReferenceBuildId(build.getExternalizableId());

        // should have the same parent commit as the previous build, as no commits have been made
        Run<?, ?> lastBuild = buildSuccessfully(job);
        GitCommitsRecord lastRecord = lastBuild.getAction(GitCommitsRecord.class);
        ReferenceBuild lastRefBuild = lastBuild.getAction(ReferenceBuild.class);
        assertThat(lastRecord).isNotNull()
                .hasParentCommit(currentCommit);
        assertThat(lastRefBuild).isNotNull()
                .hasReferenceBuildId(build.getExternalizableId());
    }

    @Test
    public void testMultiBranch() throws IOException {
        // Init commit
        String currentCommit = getHead();
        FreeStyleProject job = createFreeStyleProject("MultiBranch");

        // root build, should have no reference build
        Run<?, ?> masterBuild = buildSuccessfully(job);


        checkoutNewBranch("side");
        writeFileAsAuthorBar("Added new file to the side branch");

        // parent commit should point to root build
        Run<?, ?> sideBuild = buildSuccessfully(job);
        GitCommitsRecord sideRecord = sideBuild.getAction(GitCommitsRecord.class);
        ReferenceBuild sideRefBuild = sideBuild.getAction(ReferenceBuild.class);
        assertThat(sideRecord).isNotNull()
                .hasParentCommit(currentCommit);
        assertThat(sideRefBuild).isNotNull()
                .hasOwner(sideBuild)
                .hasReferenceBuildId(masterBuild.getExternalizableId());


        checkout("master");
        writeFileAsAuthorBar("Edited file on master branch 1");

        // parent commit should still point to root build, as the master branch now has an available build
        Run<?, ?> masterBuild2 = buildSuccessfully(job);
        GitCommitsRecord masterRecord2 = masterBuild2.getAction(GitCommitsRecord.class);
        ReferenceBuild masterRefBuild2 = masterBuild2.getAction(ReferenceBuild.class);
        assertThat(masterRecord2).isNotNull()
                .hasParentCommit(currentCommit);
        assertThat(masterRefBuild2).isNotNull()
                .hasOwner(masterBuild2)
                .hasReferenceBuildId(masterBuild.getExternalizableId());


        // new branch feat has now the latest commit from the side branch as parent
        checkout("side");
        checkoutNewBranch("feat");
        writeFileAsAuthorBar("side2 branch created and new file added");

        // The reference build should now point to the second build, as the parent commit is from the branch side
        Run<?, ?> featBuild = buildSuccessfully(job);
        GitCommitsRecord featRecord = featBuild.getAction(GitCommitsRecord.class);
        ReferenceBuild featRefBuild = featBuild.getAction(ReferenceBuild.class);
        assertThat(featRecord).isNotNull()
                .hasParentCommit(sideRecord.getLatestCommit());
        assertThat(featRefBuild).isNotNull()
                .hasOwner(featBuild)
                .hasReferenceBuildId(sideBuild.getExternalizableId());
    }

    @Test
    public void testMultiBranch2() throws IOException {
        /*  - commit #1 master
            - checkout new branch side
            - checkout master
            - commit #2 master
            - build master (#1, contains commits #1 and #2)
            - checkout side
            - commit #1 side
            - build side (#2)
            --> Then the parent build is not the latest commit of #1 */

        // Init commit
        String currentCommit = getHead();
        FreeStyleProject job = createFreeStyleProject("MultiBranchTwo");

        checkoutNewBranch("side");
        checkout("master");
        writeFileAsAuthorBar("Second commit to master");
        Run<?, ?> firstMasterBuild = buildSuccessfully(job);
        GitCommitsRecord firstMasterRecord = firstMasterBuild.getAction(GitCommitsRecord.class);
        ReferenceBuild firstMasterRefBuild = firstMasterBuild.getAction(ReferenceBuild.class);
        // master has 2 commits, but only one build -> parent commit, but no reference build
        assertThat(firstMasterRecord).isNotNull()
                .hasParentCommit(currentCommit);
        assertThat(firstMasterRefBuild).isNotNull()
                .hasOwner(firstMasterBuild)
                .doesNotHaveReferenceBuild();

        checkout("side");
        writeFileAsAuthorBar("Second commit to side");
        Run<?, ?> firstSideBuild = buildSuccessfully(job);
        GitCommitsRecord firstSideRecord = firstSideBuild.getAction(GitCommitsRecord.class);
        ReferenceBuild firstSideRefBuild = firstSideBuild.getAction(ReferenceBuild.class);
        assertThat(firstSideRecord).isNotNull()
                .hasParentCommit(currentCommit);
        assertThat(firstSideRefBuild).isNotNull()
                .hasOwner(firstSideBuild)
                .hasReferenceBuildId(firstMasterBuild.getExternalizableId());
        // First build contains the parent commit, so the first build has to be selected as reference build
    }

    private FreeStyleProject createFreeStyleProject(final String jobName) throws IOException {
        FreeStyleProject project = createProject(FreeStyleProject.class, jobName);

        GitReferenceRecorder recorder = new GitReferenceRecorder();
        recorder.setReferenceJob(jobName);

        project.getPublishersList().add(recorder);
        project.setScm(new GitSCM(sampleRepo.toString()));
        return project;
    }
}