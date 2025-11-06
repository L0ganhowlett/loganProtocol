
#  **Project ORION – The Agentic Orchestration Engine**
*An AI-driven protocol for autonomous agent collaboration and orchestration*

[![License](https://img.shields.io/github/license/L0ganhowlett/loganProtocol)](LICENSE)
[![AWS](https://img.shields.io/badge/Built_on-AWS_Bedrock-orange)](#)
[![Java](https://img.shields.io/badge/Backend-Java_21_&_SpringBoot-blue)](#)
[![LLM](https://img.shields.io/badge/LLM-Nova_Bedrock-green)](#)
[![Status](https://img.shields.io/badge/Status-Active-success)](#)

---

##  **Setup & Installation**

This guide walks you through setting up **Project ORION** locally with **Eureka Discovery**, **Kernel Service**, **Bedrock Agents**, and **MySQL**.

---

###  **1. Prerequisites**

| Tool | Version | Description |
|------|----------|--------------|
| **Java JDK** | 21+ | Required to run Spring Boot microservices |
| **Maven** | 3.9+ | Build tool for packaging services |
| **MySQL** | 8.0+ | Persistent store for orchestration data |
| **Redis** | 7.0+ | (Optional) Used for pub/sub orchestration |
| **AWS Account** | — | Required for Bedrock access |
| **Git** | Latest | To clone and manage repository |

---

###  **2. Clone the Repository**

```bash
git clone https://github.com/L0ganhowlett/loganProtocol.git
cd Project-ORION
```

---

###  **3. Set Up MySQL Database**

1. Start your local MySQL instance.
2. Create a database and user for the kernel:

```sql
CREATE DATABASE agentdb;
CREATE USER 'agent'@'%' IDENTIFIED BY 'agentpass';
GRANT ALL PRIVILEGES ON agentdb.* TO 'agent'@'%';
FLUSH PRIVILEGES;
```

3. Verify connection from CLI or Workbench:
```bash
mysql -u agent -p agentdb
```

---

###  **4. Start the Discovery Service (Eureka)**

The Discovery Service enables all agents and services to **register and discover each other dynamically**.

```bash
cd discovery-service
java -jar target/discovery-service.jar
```

Default port: **`8761`**  
Visit **[http://localhost:8761](http://localhost:8761)** to confirm the dashboard is running.  
You should see an empty registry that will soon populate as services start.

---

###  **5. Start the Kernel Service**

The **Kernel Service** is the **orchestration brain** — it handles registration, orchestration, and communication with Bedrock agents.

Configuration file: `/orion-kernel/src/main/resources/application.yml`

```yaml
kernel:
  id: kernel-1

spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/agentdb
    username: agent
    password: agentpass
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQL8Dialect
  flyway:
    enabled: true
    locations: classpath:db/migration
  application:
    name: kernel-service

eureka:
  client:
    register-with-eureka: true
    fetch-registry: true
    should-unregister-on-shutdown: true
    service-url:
      defaultZone: http://localhost:8761/eureka/
  instance:
    prefer-ip-address: true
    lease-renewal-interval-in-seconds: 10
    lease-expiration-duration-in-seconds: 30

server:
  port: 8080
```

Then build and run:

```bash
cd orion-kernel
mvn clean package -DskipTests
java -jar target/kernel-service.jar
```

The kernel will:
- Register itself with **Eureka**
- Connect to **MySQL**
- Initialize orchestration memory/state
- Automatically **spawn Bedrock-Agent instances** as defined internally

---

### **6. Bedrock Agent (Auto-Spawned)**

When the **Kernel Service** starts, it automatically launches internal **Bedrock Agent instances**, each representing a specific task domain (e.g., Jenkins, MSME Billing, Validation).

Configuration example (`/orion-bedrock-agent/src/main/resources/application.yml`):

```yaml
kernel:
  id: kernel-1

eureka:
  client:
    register-with-eureka: true
    fetch-registry: true
    should-unregister-on-shutdown: true
    service-url:
      defaultZone: http://localhost:8761/eureka/
  instance:
    prefer-ip-address: true
    lease-renewal-interval-in-seconds: 10
    lease-expiration-duration-in-seconds: 30
    ip-address: YOUR_INSTANCE_IP
    non-secure-port: ${server.port}
    instance-id: ${spring.application.name}:${server.port}

aws:
  bedrock:
    region: us-east-1   # Change to your actual Bedrock region
    credentials:
      accessKeyId: YOUR_AWS_ACCESS_KEY_ID
      secretAccessKey: YOUR_AWS_SECRET_ACCESS_KEY

server:
  port: 0   # Uses random ports for auto-spawned agents
```

Each Bedrock agent:
- Registers with **Eureka** dynamically
- Communicates with the Kernel for orchestration tasks
- Uses **AWS Bedrock models** for reasoning

Check the Eureka dashboard — you should see entries like:

| Application | Status | Port |
|--------------|---------|------|
| KERNEL-SERVICE | ✅ UP | 8080 |
| BEDROCK-AGENT-1 | ✅ UP | 51421 |
| BEDROCK-AGENT-2 | ✅ UP | 51422 |

---

### **7. Verify Orchestration Flow**

Once **Kernel** and **Discovery** are up, test orchestration using cURL:

```bash
curl -X POST "http://localhost:8080/api/orchestrate"      -H "Content-Type: application/json"      -d '{"goal":"Create a new invoice for vendor Zenith Retailers with amount 45000"}'
```

Expected response:
```json
{
  "status": "RUNNING",
  "agents": ["InvoiceAgent", "NotificationAgent"],
  "context": "Invoice INV-1042 created and vendor notified"
}
```

---

### **8. Validate System Health**

Visit **Eureka Console** → [http://localhost:8761](http://localhost:8761)

You should see something like:

| Application | Status | Port |
|--------------|---------|------|
| KERNEL-SERVICE | ✅ UP | 8080 |
| BEDROCK-AGENT-1 | ✅ UP | 51421 |
| BEDROCK-AGENT-2 | ✅ UP | 51422 |

This confirms successful orchestration.

---

### **9. Stopping the Services**

To gracefully shut down:

```bash
# Stop in reverse order
Ctrl + C (Kernel)
Ctrl + C (Eureka)
```

This ensures that agents unregister properly from the registry.

---

## **Maintainer**

**Adwait Laud ([@L0ganhowlett](https://github.com/L0ganhowlett))**  
*AI Systems Engineer • AWS Agentic Protocols Researcher*

---

## **License**

This project is licensed under the [MIT License](LICENSE).
