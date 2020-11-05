package io.jenkins.plugins.forensics.git.reference;

import hudson.model.FreeStyleProject;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

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
        // Commit A
        String commitA = getHead();
        FreeStyleProject job = createFreeStyleProject("SingleBranch");

        // Build 1: [A]
        Run<?, ?> build1 = buildSuccessfully(job);
        // Commit A has no parent commit and therefore build1 has no reference build
        assertThat(build1.getAction(GitCommitsRecord.class)).isNotNull();
        assertThat(build1.getAction(ReferenceBuild.class)).isNotNull()
                .hasOwner(build1)
                .doesNotHaveReferenceBuild();

        // Commit B
        writeFileAsAuthorBar("Commit B in master");

        // Build 2: [B]
        Run<?, ?> build2 = buildSuccessfully(job);
        assertThat(build2.getAction(GitCommitsRecord.class)).isNotNull();
        assertThat( build2.getAction(ReferenceBuild.class)).isNotNull()
                .hasReferenceBuildId(build1.getExternalizableId());

        // Build 3: [B]
        // build3 should have the same parent commit as the previous build, as no new commits have been made
        Run<?, ?> build3 = buildSuccessfully(job);
        assertThat(build3.getAction(GitCommitsRecord.class)).isNotNull();
        assertThat(build3.getAction(ReferenceBuild.class)).isNotNull()
                .hasReferenceBuildId(build1.getExternalizableId());
    }

    @Test
    public void testMultiBranch() throws IOException {
        // Commit A
        String commitA = getHead();
        FreeStyleProject job = createFreeStyleProject("MultiBranch");

        // build1 has no reference build
        Run<?, ?> build1 = buildSuccessfully(job);

        checkoutNewBranch("feature");
        // Commit B
        writeFileAsAuthorBar("Commit B in feature branch");

        // reference build should point to build1
        Run<?, ?> build2 = buildSuccessfully(job);
        assertThat(build2.getAction(GitCommitsRecord.class)).isNotNull();
        assertThat( build2.getAction(ReferenceBuild.class)).isNotNull()
                .hasOwner(build2)
                .hasReferenceBuildId(build1.getExternalizableId());

        checkout("master");
        // Commit C
        writeFileAsAuthorBar("Commit C in master branch");

        // Reference build is build1 from master branch
        Run<?, ?> build3 = buildSuccessfully(job);
        assertThat(build3.getAction(GitCommitsRecord.class)).isNotNull();
        assertThat(build3.getAction(ReferenceBuild.class)).isNotNull()
                .hasOwner(build3)
                .hasReferenceBuildId(build1.getExternalizableId());
    }

    @Test
    public void testMultiBranch2() throws IOException {
        /*  - commit A master
            - checkout new branch feature
            - checkout master
            - commit B master
            - build master (#1, contains commits A and B)
            - checkout feature
            - commit C feature
            - build feature #2  */

        // Commit A
        String commitA = getHead();
        FreeStyleProject job = createFreeStyleProject("MultiBranchTwo");

        checkoutNewBranch("feature");

        checkout("master");
        // Commit B
        writeFileAsAuthorBar("Commit B in master branch");

        Run<?, ?> build1 = buildSuccessfully(job);
        // master has commits [A, B] , but only one build -> no reference build
        assertThat(build1.getAction(GitCommitsRecord.class)).isNotNull();
        assertThat(build1.getAction(ReferenceBuild.class)).isNotNull()
                .hasOwner(build1)
                .doesNotHaveReferenceBuild();

        checkout("feature");
        // Commit C
        writeFileAsAuthorBar("Commit B in feature branch");
        Run<?, ?> build2 = buildSuccessfully(job);
        // build #1 contains the parent commit -> the build #1 is reference build
        assertThat(build2.getAction(GitCommitsRecord.class)).isNotNull();
        assertThat(build2.getAction(ReferenceBuild.class)).isNotNull()
                .hasOwner(build2)
                .hasReferenceBuildId(build1.getExternalizableId());
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