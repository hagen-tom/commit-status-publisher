/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.commitPublisher.github;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Pair;
import jetbrains.buildServer.commitPublisher.github.api.*;
import jetbrains.buildServer.commitPublisher.github.ui.UpdateChangesConstants;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.serverSide.executors.ExecutorServices;
import jetbrains.buildServer.util.ExceptionUtil;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.vcs.VcsRootInstance;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by Eugene Petrenko (eugene.petrenko@gmail.com)
 * Date: 06.09.12 3:29
 */
public class ChangeStatusUpdater {
  private static final Logger LOG = Logger.getInstance(ChangeStatusUpdater.class.getName());
  private static final UpdateChangesConstants C = new UpdateChangesConstants();
  private static final Pattern SCP_PATTERN = Pattern.compile("git@[^:]+:([^/]+)/(.+)");

  private final ExecutorService myExecutor;
  @NotNull
  private final GitHubApiFactory myFactory;
  private final WebLinks myWeb;

  public ChangeStatusUpdater(@NotNull final ExecutorServices services,
                             @NotNull final GitHubApiFactory factory,
                             @NotNull final WebLinks web) {
    myFactory = factory;
    myWeb = web;
    myExecutor = services.getLowPriorityExecutorService();
  }

  @NotNull
  private GitHubApi getGitHubApi(@NotNull Map<String, String> params) {
    final String serverUrl = params.get(C.getServerKey());
    if (serverUrl == null || StringUtil.isEmptyOrSpaces(serverUrl)) {
      throw new IllegalArgumentException("Failed to read GitHub URL from the feature settings");
    }

    final GitHubApiAuthenticationType authenticationType = GitHubApiAuthenticationType.parse(params.get(C.getAuthenticationTypeKey()));
    switch (authenticationType) {
      case PASSWORD_AUTH:
        final String username = params.get(C.getUserNameKey());
        final String password = params.get(C.getPasswordKey());
        return myFactory.openGitHubForUser(serverUrl, username, password);

      case TOKEN_AUTH:
        final String token = params.get(C.getAccessTokenKey());
        return myFactory.openGitHubForToken(serverUrl, token);

      default:
        throw new IllegalArgumentException("Failed to parse authentication type:" + authenticationType);
    }
  }

  @Nullable
  public Handler getUpdateHandler(@NotNull VcsRootInstance root, @NotNull Map<String, String> params) {
    final GitHubApi api = getGitHubApi(params);

    GitHubRepo repo = getGitHubRepo(root.getProperty("url"));
    if (repo == null)
      return null;

    final String repositoryOwner = repo.owner();
    final String repositoryName = repo.repositoryName();
    final String context = "continuous-integration/teamcity";
    final boolean addComments = !StringUtil.isEmptyOrSpaces(params.get(C.getUseCommentsKey()));
    final boolean useGuestUrls = !StringUtil.isEmptyOrSpaces(params.get(C.getUseGuestUrlsKey()));

    final GitHubApiReportEvent reportEvent = GitHubApiReportEvent.parse(params.get(C.getReportOnKey()));
    final boolean shouldReportOnStart = reportEvent == GitHubApiReportEvent.ON_START_AND_FINISH || reportEvent == GitHubApiReportEvent.ON_START;
    final boolean shouldReportOnFinish = reportEvent == GitHubApiReportEvent.ON_START_AND_FINISH || reportEvent == GitHubApiReportEvent.ON_FINISH;

    return new Handler() {
      @NotNull
      private String getViewResultsUrl(@NotNull final SBuild build) {
        final String url = myWeb.getViewResultsUrl(build);
        if (useGuestUrls) {
          return url + (url.contains("?") ? "&" : "?") + "guest=1";
        }
        return url;
      }

      public boolean shouldReportOnStart() {
        return shouldReportOnStart;
      }

      public boolean shouldReportOnFinish() {
        return shouldReportOnFinish;
      }

      public void scheduleChangeStarted(@NotNull RepositoryVersion version, @NotNull SBuild build) {
        scheduleChangeUpdate(version, build, "Started TeamCity Build " + build.getFullName(), GitHubChangeState.Pending);
      }

      public void scheduleChangeCompeted(@NotNull RepositoryVersion version, @NotNull SBuild build) {
        LOG.debug("Status :" + build.getStatusDescriptor().getStatus().getText());
        LOG.debug("Status Priority:" + build.getStatusDescriptor().getStatus().getPriority());

        final GitHubChangeState status = getGitHubChangeState(build);
        final String text = getGitHubChangeText(build);
        scheduleChangeUpdate(version, build, "Finished TeamCity Build " + build.getFullName() + " " + text, status);
      }

      @NotNull
      private String getGitHubChangeText(@NotNull final SBuild build) {
        final String text = build.getStatusDescriptor().getText();
        if (text != null) {
          return ": " + text;
        } else {
          return "";
        }
      }

      @NotNull
      private GitHubChangeState getGitHubChangeState(@NotNull final SBuild build) {
        final Status status = build.getStatusDescriptor().getStatus();
        final byte priority = status.getPriority();

        if (priority == Status.NORMAL.getPriority()) {
          return GitHubChangeState.Success;
        } else if (priority == Status.FAILURE.getPriority()) {
          return GitHubChangeState.Failure;
        } else {
          return GitHubChangeState.Error;
        }
      }

      private void scheduleChangeUpdate(@NotNull final RepositoryVersion version,
                                        @NotNull final SBuild build,
                                        @NotNull final String message,
                                        @NotNull final GitHubChangeState status) {
        LOG.info("Scheduling GitHub status update for " +
                "hash: " + version.getVersion() + ", " +
                "branch: " + version.getVcsBranch() + ", " +
                "buildId: " + build.getBuildId() + ", " +
                "status: " + status);

        myExecutor.submit(ExceptionUtil.catchAll("set change status on github", new Runnable() {
          @NotNull
          private String getFailureText(@Nullable final TestFailureInfo failureInfo) {
            final String no_data = "<no details avaliable>";
            if (failureInfo == null) return no_data;

            final String stacktrace = failureInfo.getShortStacktrace();
            if (stacktrace == null || StringUtil.isEmptyOrSpaces(stacktrace)) return no_data;

            return stacktrace;
          }

          @NotNull
          private String getFriendlyDuration(final long seconds) {
            long second = seconds % 60;
            long minute = (seconds / 60) % 60;
            long hour = seconds / 60 / 60;

            return String.format("%02d:%02d:%02d", hour, minute, second);
          }

          @NotNull
          private String getComment(@NotNull RepositoryVersion version,
                                    @NotNull SBuild build,
                                    boolean completed,
                                    @NotNull String hash) {
            final StringBuilder comment = new StringBuilder();
            comment.append("TeamCity ");
            final SBuildType bt = build.getBuildType();
            if (bt != null) {
              comment.append(bt.getFullName());
            }
            comment.append(" [Build ");
            comment.append(build.getBuildNumber());
            comment.append("](");
            comment.append(getViewResultsUrl(build));
            comment.append(") ");

            if (completed) {
              comment.append("outcome was **").append(build.getStatusDescriptor().getStatus().getText()).append("**");
            } else {
              comment.append("is now running");
            }

            comment.append("\n");

            final String text = build.getStatusDescriptor().getText();
            if (completed && text != null) {
              comment.append("Summary: ");
              comment.append(text);
              comment.append(" Build time: ");
              comment.append(getFriendlyDuration(build.getDuration()));

              if (build.getBuildStatus() != Status.NORMAL) {

                final List<STestRun> failedTests = build.getFullStatistics().getFailedTests();
                if (!failedTests.isEmpty()) {
                  comment.append("\n### Failed tests\n");
                  comment.append("```\n");

                  for (int i = 0; i < failedTests.size(); i++) {
                    final STestRun testRun = failedTests.get(i);
                    comment.append("");
                    comment.append(testRun.getTest().getName().toString());
                    comment.append(": ");
                    comment.append(getFailureText(testRun.getFailureInfo()));
                    comment.append("\n\n");

                    if (i == 10) {
                      comment.append("\n##### there are ")
                              .append(build.getFullStatistics().getFailedTestCount() - i)
                              .append(" more failed tests, see build details\n");
                      break;
                    }
                  }
                  comment.append("```\n");
                }
              }
            }

            return comment.toString();
          }

          @NotNull
          private String resolveCommitHash() {
            final String vcsBranch = version.getVcsBranch();
            if (vcsBranch != null && api.isPullRequestMergeBranch(vcsBranch)) {
              try {
                final String hash = api.findPullRequestCommit(repositoryOwner, repositoryName, vcsBranch);
                if (hash == null) {
                  throw new IOException("Failed to find head hash for commit from " + vcsBranch);
                }
                LOG.info("Resolved GitHub change commit for " + vcsBranch + " to point to pull request head for " +
                        "hash: " + version.getVersion() + ", " +
                        "newHash: " + hash + ", " +
                        "branch: " + version.getVcsBranch() + ", " +
                        "buildId: " + build.getBuildId() + ", " +
                        "status: " + status);
                return hash;
              } catch (IOException e) {
                LOG.warn("Failed to find status update hash for " + vcsBranch + " for repository " + repositoryName);
              }
            }
            return version.getVersion();
          }

          public void run() {
            final String hash = resolveCommitHash();
            try {
              api.setChangeStatus(
                      repositoryOwner,
                      repositoryName,
                      hash,
                      status,
                      getViewResultsUrl(build),
                      message,
                      context
              );
              LOG.info("Updated GitHub status for hash: " + hash + ", buildId: " + build.getBuildId() + ", status: " + status);
            } catch (IOException e) {
              LOG.warn("Failed to update GitHub status for hash: " + hash + ", buildId: " + build.getBuildId() + ", status: " + status + ". " + e.getMessage(), e);
            }
            if (addComments) {
              try {
                api.postComment(
                        repositoryOwner,
                        repositoryName,
                        hash,
                        getComment(version, build, status != GitHubChangeState.Pending, hash)
                );
                LOG.info("Added comment to GitHub commit: " + hash + ", buildId: " + build.getBuildId() + ", status: " + status);
              } catch (IOException e) {
                LOG.warn("Failed add GitHub comment for branch: " + version.getVcsBranch() + ", buildId: " + build.getBuildId() + ", status: " + status + ". " + e.getMessage(), e);
              }
            }
          }
        }));
      }
    };
  }

  public static interface Handler {
    boolean shouldReportOnStart();
    boolean shouldReportOnFinish();
    void scheduleChangeStarted(@NotNull final RepositoryVersion hash, @NotNull final SBuild build);
    void scheduleChangeCompeted(@NotNull final RepositoryVersion hash, @NotNull final SBuild build);
  }


  @Nullable
  public static GitHubRepo getGitHubRepo(@NotNull String uri) {
    if (uri.startsWith("git@")) {
      Matcher m = SCP_PATTERN.matcher(uri);
      if(!m.matches()) {
        LOG.warn("Cannot parse GitHub repository url " + uri);
        return null;
      }
      String userGroup = m.group(1);
      String repo = m.group(2);
      if (repo.endsWith(".git"))
        repo = repo.substring(0, repo.length() - 4);
      return new GitHubRepo(userGroup, repo);
    }

    URL url = null;
    try {
      url = new URL(uri);
    } catch (MalformedURLException e) {
      LOG.warn("Cannot parse GitHub repository url " + uri, e);
      return null;
    }

    String path = url.getPath();
    if (path == null) {
      LOG.warn("Cannot parse GitHub repository url " + uri + ", path is empty");
      return null;
    }

    if (path.startsWith("/"))
      path = path.substring(1);
    int idx = path.indexOf("/");
    if (idx <= 0) {
      LOG.warn("Cannot parse GitHub repository url " + uri);
      return null;
    }
    String owner = path.substring(0, idx);
    String repo = path.substring(idx + 1, path.length());
    if (repo.endsWith(".git"))
      repo = repo.substring(0, repo.length() - 4);
    return new GitHubRepo(owner, repo);
  }


  public static class GitHubRepo extends Pair<String, String> {
    public GitHubRepo(@NotNull String owner, @NotNull String repo) {
      super(owner, repo);
    }

    @NotNull
    public String owner() {
      return first;
    }
    @NotNull
    public String repositoryName() {
      return second;
    }
  }
}
