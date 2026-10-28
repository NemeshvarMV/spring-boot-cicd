pipeline {
    agent any

    environment {
        AWS_REGION     = 'eu-north-1'                      // Replace with your region
        AWS_ACCOUNT_ID = '524109816304'                   // Replace with your AWS Account ID
        REPO_NAME      = 'spring-boot-app'
        ECR_REGISTRY   = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
        IMAGE_NAME     = "${ECR_REGISTRY}/${REPO_NAME}"
        IMAGE_TAG      = "${BUILD_NUMBER}"
        AWS_CRED_ID    = 'aws-credentials'
        EC2_SSH_CRED   = 'ec2-ssh-key'
        EC2_HOST       = 'ubuntu@51.20.69.194'         // Replace with your EC2 public IP
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }
        stage('Maven Build') {
            steps {
                sh 'mvn clean package -DskipTests'
            }
        }
        stage('Docker Build') {
            steps {
                sh "docker build -t ${IMAGE_NAME}:${IMAGE_TAG} ."
                sh "docker tag ${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_NAME}:latest"
            }
        }
        stage('Push to ECR') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: "${AWS_CRED_ID}"]]) {
                    sh """
                        aws ecr get-login-password --region ${AWS_REGION} | docker login --username AWS --password-stdin ${ECR_REGISTRY}
                        docker push ${IMAGE_NAME}:${IMAGE_TAG}
                        docker push ${IMAGE_NAME}:latest
                    """
                }
            }
        }
        stage('Deploy with Docker Compose') {
            steps {
                sshagent([ "${EC2_SSH_CRED}" ]) {
                    sh """
                        ssh -o StrictHostKeyChecking=no ${EC2_HOST} << 'EOF'
                            aws ecr get-login-password --region ${AWS_REGION} | docker login --username AWS --password-stdin ${ECR_REGISTRY}
                            export IMAGE_NAME=${IMAGE_NAME}
                            export IMAGE_TAG=${IMAGE_TAG}
                            cd /home/ubuntu/app
                            docker compose pull spring-app
                            docker compose up -d --remove-orphans
                            docker image prune -f
EOF
                    """
                }
            }
        }
    }
}