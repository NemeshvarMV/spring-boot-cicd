pipeline {
    agent any

    environment {
        // Safe default placeholders (Overridden dynamically by .env or Jenkins Global Environment)
        AWS_REGION     = 'us-east-1'
        AWS_ACCOUNT_ID = '123456789012'
        REPO_NAME      = 'spring-boot-app'
        IMAGE_TAG      = "${BUILD_NUMBER}"
        AWS_CRED_ID    = 'aws-credentials'
        EC2_SSH_CRED   = 'ec2-ssh-key'
        EC2_HOST       = 'ubuntu@<EC2_PUBLIC_IP>'
        APP_PORT       = '8081'
    }

    stages {
        stage('Checkout') {
            steps {
                echo 'Checking out source code from GitHub...'
                checkout scm
            }
        }

        stage('Load Environment Config') {
            steps {
                script {
                    def envFileContent = ""
                    if (fileExists('.env')) {
                        echo 'Found local .env file in workspace. Loading configuration...'
                        envFileContent = readFile('.env')
                    } else if (fileExists('/var/jenkins_home/.env')) {
                        echo 'Found .env file in /var/jenkins_home/. Loading configuration...'
                        envFileContent = readFile('/var/jenkins_home/.env')
                    } else {
                        echo 'No .env file found. Using Jenkins environment variables or defaults.'
                    }

                    if (envFileContent) {
                        envFileContent.split('\n').each { line ->
                            line = line.trim()
                            if (line && !line.startsWith('#') && line.contains('=')) {
                                def parts = line.split('=', 2)
                                def key = parts[0].trim()
                                def val = parts[1].trim()
                                env."${key}" = val
                            }
                        }
                    }

                    // Dynamically calculate ECR and Image coordinates
                    env.ECR_REGISTRY = "${env.AWS_ACCOUNT_ID}.dkr.ecr.${env.AWS_REGION}.amazonaws.com"
                    env.IMAGE_NAME   = "${env.ECR_REGISTRY}/${env.REPO_NAME}"
                    echo "Target ECR: ${env.IMAGE_NAME}:${env.IMAGE_TAG}"
                    echo "Target EC2 Host: ${env.EC2_HOST}"
                }
            }
        }

        stage('Maven Build') {
            steps {
                echo 'Compiling and packaging Spring Boot application...'
                sh 'mvn clean package -DskipTests'
            }
        }

        stage('Docker Build') {
            steps {
                echo "Building Docker image: ${env.IMAGE_NAME}:${env.IMAGE_TAG}..."
                sh "docker build -t ${env.IMAGE_NAME}:${env.IMAGE_TAG} ."
                sh "docker tag ${env.IMAGE_NAME}:${env.IMAGE_TAG} ${env.IMAGE_NAME}:latest"
            }
        }

        stage('Push to registry (ECR)') {
            steps {
                script {
                    echo "Authenticating and pushing image to AWS ECR..."
                    try {
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: "${env.AWS_CRED_ID}"]]) {
                            sh """
                                aws ecr get-login-password --region ${env.AWS_REGION} | docker login --username AWS --password-stdin ${env.ECR_REGISTRY}
                                docker push ${env.IMAGE_NAME}:${env.IMAGE_TAG}
                                docker push ${env.IMAGE_NAME}:latest
                            """
                        }
                    } catch (Exception e) {
                        echo "[INFO] ECR push skipped (${e.message}). Proceeding with local Docker image deployment."
                    }
                }
            }
        }

        stage('Deploy using Docker Compose') {
            steps {
                echo "Deploying to EC2 host (${env.EC2_HOST}) using Docker Compose..."
                sshagent([ "${env.EC2_SSH_CRED}" ]) {
                    sh """
                        # 1. Ensure target deployment directory exists on EC2
                        ssh -o StrictHostKeyChecking=no ${env.EC2_HOST} "mkdir -p /home/ubuntu/app"

                        # 2. Transfer docker-compose.yml to target host
                        scp -o StrictHostKeyChecking=no docker-compose.yml ${env.EC2_HOST}:/home/ubuntu/app/docker-compose.yml

                        # 3. Authenticate with ECR if AWS CLI is configured
                        if command -v aws >/dev/null 2>&1; then
                            ECR_TOKEN=\$(aws ecr get-login-password --region ${env.AWS_REGION} 2>/dev/null || true)
                            if [ -n "\$ECR_TOKEN" ]; then
                                ssh -o StrictHostKeyChecking=no ${env.EC2_HOST} "echo '\$ECR_TOKEN' | docker login --username AWS --password-stdin ${env.ECR_REGISTRY} 2>/dev/null || true"
                            fi
                        fi

                        # 4. Pull latest image if registry exists, restart stack with Docker Compose
                        ssh -o StrictHostKeyChecking=no ${env.EC2_HOST} "
                            cd /home/ubuntu/app
                            export IMAGE_NAME=${env.IMAGE_NAME}
                            export IMAGE_TAG=${env.IMAGE_TAG}
                            export APP_PORT=${env.APP_PORT}
                            docker compose pull spring-app 2>/dev/null || true
                            docker compose up -d --remove-orphans
                            docker image prune -f

                            echo 'Containers running on EC2:'
                            docker compose ps
                        "
                    """
                }
            }
        }
    }

    post {
        success {
            echo "Pipeline succeeded! Application deployed to http://${env.EC2_HOST.split('@')[1]}:${env.APP_PORT}"
        }
        failure {
            echo "Pipeline failed. Check stage logs for troubleshooting."
        }
    }
}
