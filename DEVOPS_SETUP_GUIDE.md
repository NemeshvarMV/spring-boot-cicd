# Complete DevOps Guide: Spring Boot CI/CD with Docker, MySQL & AWS (Part 2 - #36)

This guide walks you through setting up and running the complete automated CI/CD pipeline for the Spring Boot + MySQL application using Jenkins, AWS ECR, and Docker Compose on EC2.

---

## 1. Architecture Overview

```mermaid
flowchart TD
    DEV["Developer"] -->|git push| GH["GitHub Repository"]
    GH -->|Webhook Trigger: /github-webhook/| JNK["Jenkins on EC2 (:8080)"]
    
    subgraph Jenkins Pipeline
        JNK --> S1["1. Checkout Code"]
        S1 --> S2["2. Maven Build (mvn package)"]
        S2 --> S3["3. Docker Build (Multi-stage)"]
        S3 --> S4["4. Push Image to AWS ECR"]
        S4 --> S5["5. SSH & Deploy via Docker Compose"]
    end
    
    S4 --> ECR[("AWS ECR Repository")]
    
    subgraph EC2 Host Deployment
        S5 -->|scp docker-compose.yml| APP_DIR["/home/ubuntu/app/"]
        S5 -->|docker compose pull & up -d| STACK["Running Containers"]
        ECR -->|Pull Image| STACK
        
        subgraph Docker Bridge Network
            APP["spring-app (Spring Boot :8081)"]
            DB[("mysqldb (MySQL 8.0 :3306)")]
            VOL[("Volume: mysql_data")]
            APP -->|jdbc:mysql://mysqldb:3306/studentdb| DB
            DB --- VOL
        end
    end
```

> [!NOTE]
> **Port Separation**: Jenkins runs on port **`8080`**, while the Spring Boot application runs on port **`8081`**. This prevents port collision when running both on the same EC2 instance!

---

## 2. Step-by-Step Setup Instructions

### Step 1: AWS ECR Repository Setup
1. Log in to the **AWS Management Console**.
2. Navigate to **Amazon ECR** (Elastic Container Registry).
3. Click **Create repository**:
   - Visibility: **Private**
   - Repository name: `spring-boot-app`
   - Tag immutability: **Mutable (Disabled)**
4. Copy your repository URI (e.g. `524109816304.dkr.ecr.eu-north-1.amazonaws.com/spring-boot-app`).
5. Ensure you have an IAM User with the policy **`AmazonEC2ContainerRegistryPowerUser`**. Keep your `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`.

---

### Step 2: EC2 Instance Provisioning
1. Launch an **Ubuntu 24.04 LTS** EC2 instance (`t3.micro` Free Tier).
2. Configure **Security Group** inbound rules:
   | Type | Port | Source | Description |
   | :--- | :--- | :--- | :--- |
   | **SSH** | `22` | `0.0.0.0/0` | Remote SSH terminal access |
   | **Custom TCP** | `8080` | `0.0.0.0/0` | Jenkins Dashboard & GitHub Webhook |
   | **Custom TCP** | `8081` | `0.0.0.0/0` | Spring Boot REST API Endpoint |
   | **Custom TCP** | `3306` | Optional / My IP | MySQL direct access (optional) |
3. Storage: **25–30 GiB gp3** (Free Tier eligible).
4. Key pair: Download `ec2-ssh-key.pem`.

---

### Step 3: Configure EC2 with Swap, Docker & Jenkins

SSH into your EC2 instance from PowerShell:
```powershell
ssh -i ec2-ssh-key.pem ubuntu@<EC2_PUBLIC_IP>
```

#### 1. Add 4 GB Swap Memory (Crucial for `t3.micro`):
```bash
sudo fallocate -l 4G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

#### 2. Install Docker, Docker Compose, and AWS CLI:
```bash
sudo apt update -y
sudo apt install -y docker.io docker-compose-v2
sudo snap install aws-cli --classic
sudo systemctl enable --now docker
sudo usermod -aG docker ubuntu
```

#### 3. Run Jenkins in Docker (with Docker socket mounted):
```bash
sudo docker run -d \
  --name jenkins \
  --restart=always \
  -p 8080:8080 -p 50000:50000 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v $(which docker):/usr/bin/docker \
  -v jenkins_home:/var/jenkins_home \
  -u root \
  jenkins/jenkins:lts
```

Get your initial Jenkins administrator password:
```bash
sudo docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

#### 4. Copy your `.env` file to EC2 (from your local laptop):
From PowerShell on your laptop in `D:\spring-boot-cicd`:
```powershell
ssh -i ec2-ssh-key.pem ubuntu@<EC2_PUBLIC_IP> "mkdir -p /home/ubuntu/app"
scp -i ec2-ssh-key.pem .env ubuntu@<EC2_PUBLIC_IP>:/home/ubuntu/app/.env
```

---

### Step 4: Configure Jenkins Dashboard

1. Open your browser: `http://<EC2_PUBLIC_IP>:8080`.
2. Enter the administrator password retrieved above and select **Install suggested plugins**.
3. Install required plugins under **Manage Jenkins** $\rightarrow$ **Plugins** $\rightarrow$ **Available Plugins**:
   - **Docker Pipeline**
   - **CloudBees AWS Credentials**
   - **SSH Agent Plugin**
   - **Pipeline**
4. Add Credentials under **Manage Jenkins** $\rightarrow$ **Credentials** $\rightarrow$ **System** $\rightarrow$ **Global credentials**:
   * **Credential 1: AWS Credentials**
     - Kind: **AWS Credentials**
     - ID: `aws-credentials`
     - Access Key ID: `<YOUR_AWS_ACCESS_KEY_ID>`
     - Secret Access Key: `<YOUR_AWS_SECRET_ACCESS_KEY>`
   * **Credential 2: EC2 SSH Key**
     - Kind: **SSH Username with private key**
     - ID: `ec2-ssh-key`
     - Username: `ubuntu`
     - Private Key: Paste the full text of your `ec2-ssh-key.pem` file.

---

### Step 5: Configure GitHub Webhook

1. In your GitHub repository, go to **Settings** $\rightarrow$ **Webhooks** $\rightarrow$ **Add webhook**.
2. **Payload URL**: `http://<EC2_PUBLIC_IP>:8080/github-webhook/`
3. **Content type**: `application/json`
4. Which events would you like to trigger this webhook? Select **Just the push event**.
5. Click **Add webhook**.

---

### Step 6: Create the Jenkins Pipeline Job

1. On Jenkins Home, click **New Item**.
2. Enter name: `spring-boot-ci-cd`, select **Pipeline**, click **OK**.
3. Under **Build Triggers**, check:
   - `[x] GitHub hook trigger for GITScm polling`
4. Under **Pipeline**:
   - Definition: **Pipeline script from SCM**
   - SCM: **Git**
   - Repository URL: `https://github.com/<your-username>/<your-repo>.git`
   - Branch Specifier: `*/master` (or `*/main`)
   - Script Path: `Jenkinsfile`
5. Click **Save**.

---

## 3. End-to-End Verification & Testing

### 1. Test Initial App Deployment
Once the first build runs successfully, check running containers on EC2:
```bash
docker compose -f /home/ubuntu/app/docker-compose.yml ps
```

Test the home status endpoint:
```bash
curl http://<EC2_PUBLIC_IP>:8081/
```
Expected Output:
```json
{
  "status": "UP",
  "message": "Spring Boot CI/CD application is running successfully on Docker + EC2!",
  "endpoints": {
    "students_list": "/api/students",
    "create_student": "/api/students (POST)"
  }
}
```

### 2. Test MySQL Persistence (Student CRUD)
Insert a student record into MySQL:
```bash
curl -X POST http://<EC2_PUBLIC_IP>:8081/api/students \
  -H "Content-Type: application/json" \
  -d '{"name": "Alice Johnson", "email": "alice@gmail.com", "course": "Computer Science"}'
```

Retrieve all students:
```bash
curl http://<EC2_PUBLIC_IP>:8081/api/students
```
Expected response:
```json
[
  {
    "id": 1,
    "name": "Alice Johnson",
    "email": "alice@gmail.com",
    "course": "Computer Science"
  }
]
```

### 3. Verify Zero-Data-Loss Automated CI/CD
1. Edit `HelloController.java` (e.g., change the status message to `"Version 2.0 Live!"`).
2. Commit and push:
   ```bash
   git add .
   git commit -m "feat: release version 2.0"
   git push origin master
   ```
3. Watch Jenkins trigger immediately via the GitHub Webhook, build the new image, push to ECR, and update the EC2 container.
4. Run `curl http://<EC2_PUBLIC_IP>:8081/api/students`:
   * **Alice Johnson is still in the database!**
   * This proves your persistent volume (`mysql_data:/var/lib/mysql`) and CI/CD workflow are 100% production-ready.
