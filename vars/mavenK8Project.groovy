// vars/mavenProject.groovy
def call(body) {
    // evaluate the body block, and collect configuration into the object
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    K8_AGENT_YAML = "${config.k8_agent_yaml}"

    pipeline {
        options {
            buildDiscarder(logRotator(numToKeepStr: '5', artifactNumToKeepStr: '5'))
        }
        environment {
            PATH = "/busybox:/kaniko:$PATH"
            GITHUB_CREDENTIALS = "${config.gh_cred}"
            GITHUB_REPO = "${config.gh_repo}"
            GITHUB_BRANCH = "${config.gh_branch}"
            DOCKERFILE_REPO = "${config.dockerfile_path}"
            DOCKER_DESTINATION = "${config.docker_registry}/${config.docker_image}:${config.docker_tag}"
        }
        agent {
            kubernetes {
                defaultContainer "maven"
                yaml libraryResource("agents/k8s/${K8_AGENT_YAML}")
            }
        }
        stages {
            stage("Checkout app") {
                steps {
                    git branch: "${GITHUB_BRANCH}", credentialsId: "${GITHUB_CREDENTIALS}" , url: "${GITHUB_REPO}"
                }
            }
            stage("Build app") {
                steps {
                    sh "mvn clean package -Dmaven.test.skip=true"
                    archiveArtifacts artifacts: "target/*.jar", fingerprint: true
                    stash name: "docker", includes: "src/main/docker/Dockerfile, target/*.jar"
                }
            }
            stage("Build and Publish Image app") {
                steps {
                    container(name: "kaniko", shell: "/busybox/sh") {
                        dir("to_build") { 
                            unstash "docker"
                            sh """#!/busybox/sh
                                /kaniko/executor -f `pwd`/${DOCKERFILE_REPO} -c `pwd` --cache=true --destination ${DOCKER_DESTINATION}
                            """
                        }
                    }
                }
            }
        }
        // post {
        //     failure {
        //         mail to: "${TEAM_MAIL}",
        //             subject: "Failed Pipeline to Build Agent: ${currentBuild.fullDisplayName}",
        //             body: "Something is wrong with ${env.BUILD_URL}"
        //     }
        // }
    }
}
