// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"

// Variables
def projectNameKey = projectFolderName.toLowerCase().replace("/", "-")
def referenceAppgitRepo = "java-gft-material-api-project"
def referenceAppGitUrl = "ssh://jenkins@gerrit:29418/${PROJECT_NAME}/" + referenceAppgitRepo

// Jobs
def buildAppJob = freeStyleJob(projectFolderName + "/Reference_Application_Build")
def unitTestJob = freeStyleJob(projectFolderName + "/Reference_Application_Unit_Tests")
def codeAnalysisJob = freeStyleJob(projectFolderName + "/Reference_Application_Code_Analysis")
def deployJob = freeStyleJob(projectFolderName + "/Reference_Application_Deploy")

// Views
def pipelineView = buildPipelineView(projectFolderName + "/Java_Reference_Application")

pipelineView.with {
    title('Reference Application Pipeline')
    displayedBuilds(5)
    selectedJob(projectFolderName + "/Reference_Application_Build")
    showPipelineParameters()
    showPipelineDefinitionHeader()
    refreshFrequency(5)
}

buildAppJob.with {
    description("This job builds Java Spring reference application")
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
    }
    scm {
        git {
            remote {
                url(referenceAppGitUrl)
                credentials("adop-jenkins-master")
            }
            branch("*/master")
        }
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
    }
    label("java8")
    triggers {
        gerrit {
            events {
                refUpdated()
            }
            project(projectFolderName + '/' + referenceAppgitRepo, 'plain:master')
            configure { node ->
                node / serverName("ADOP Gerrit")
            }
        }
    }
    steps {
        maven {
            goals('clean install -DskipTests')
            mavenInstallation("ADOP Maven")
        }
    }
    publishers {
        archiveArtifacts("**/*")
        downstreamParameterized {
            trigger(projectFolderName + "/Reference_Application_Unit_Tests") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${BUILD_NUMBER}')
                    predefinedProp("PARENT_BUILD", '${JOB_NAME}')
                }
            }
        }
    }
}

unitTestJob.with {
    description("This job runs unit tests on Java Spring reference application.")
    parameters {
        stringParam("B", '', "Parent build number")
        stringParam("PARENT_BUILD", "Reference_Application_Build", "Parent build name")
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
    }
    label("java8")
    steps {
        copyArtifacts("Reference_Application_Build") {
            buildSelector {
                buildNumber('${B}')
            }
        }
        maven {
            goals('clean test')
            mavenInstallation("ADOP Maven")
        }
    }
    publishers {
        archiveArtifacts("**/*")
        downstreamParameterized {
            trigger(projectFolderName + "/Reference_Application_Code_Analysis") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${B}')
                    predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
                    predefinedProp("UTB", '${BUILD_NUMBER}')
                }
            }
        }
    }
}

codeAnalysisJob.with {
    description("This job runs code quality analysis for Java reference application using SonarQube.")
    parameters {
        stringParam("B", '', "Parent build number")
        stringParam("PARENT_BUILD", "Reference_Application_Build", "Parent build name")
        stringParam("UTB", '', "Unit Tests job build number")
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
        env('PROJECT_NAME_KEY', projectNameKey)
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
    }
    label("java8")
    steps {
        copyArtifacts('Reference_Application_Unit_Tests') {
            buildSelector {
                buildNumber('${UTB}')
            }
        }
    }
    configure { myProject ->
        myProject / builders << 'hudson.plugins.sonar.SonarRunnerBuilder'(plugin: "sonar@2.2.1") {
            project('sonar-project.properties')
            properties('''sonar.projectKey=${PROJECT_NAME_KEY}
sonar.projectName=${PROJECT_NAME}
sonar.projectVersion=1.0.${B}
sonar.sources=src/main/java
sonar.language=java
sonar.sourceEncoding=UTF-8
sonar.scm.enabled=false''')
            javaOpts()
            jdk('(Inherit From Job)')
            task()
        }
    }
    publishers {
        downstreamParameterized {
            trigger(projectFolderName + "/Reference_Application_Deploy") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${B}')
                    predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
                }
            }
        }
    }
}

deployJob.with {
    description("This job deploys the java reference application to the CI environment")
    parameters {
        stringParam("B", '', "Parent build number")
        stringParam("PARENT_BUILD", "Reference_Application_Build", "Parent build name")
        stringParam("ENVIRONMENT_NAME", "CI", "Name of the environment.")
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
    }
    label("docker")
    steps {
        copyArtifacts("Reference_Application_Build") {
            buildSelector {
                buildNumber('${B}')
                includePatterns('target/project.war')
            }
        }
        shell('''set +x
            |export SERVICE_NAME="$(echo ${PROJECT_NAME} | tr '/' '_')_${ENVIRONMENT_NAME}"
            |docker cp ${WORKSPACE}/target/project.war  ${SERVICE_NAME}:/usr/local/tomcat/webapps/
            |docker restart ${SERVICE_NAME}
            |COUNT=1
            |while ! curl -q http://${SERVICE_NAME}:8080/project -o /dev/null
            |do
            |  if [ ${COUNT} -gt 10 ]; then
            |    echo "Docker build failed even after ${COUNT}. Please investigate."
            |    exit 1
            |  fi
            |  echo "Application is not up yet. Retrying ..Attempt (${COUNT})"
            |  sleep 5
            |  COUNT=$((COUNT+1))
            |done
            |echo "=.=.=.=.=.=.=.=.=.=.=.=."
            |echo "=.=.=.=.=.=.=.=.=.=.=.=."
            |echo "Environment URL (replace PUBLIC_IP with your public ip address where you access jenkins from) : http://${SERVICE_NAME}.PUBLIC_IP.xip.io/project"
            |echo "=.=.=.=.=.=.=.=.=.=.=.=."
            |echo "=.=.=.=.=.=.=.=.=.=.=.=."
            |set -x'''.stripMargin()
        )
    }
    publishers {
        downstreamParameterized {
            trigger(projectFolderName + "/Reference_Application_Regression_Tests") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${B}')
                    predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
                    predefinedProp("ENVIRONMENT_NAME", '${ENVIRONMENT_NAME}')
                }
            }
        }
    }
}

