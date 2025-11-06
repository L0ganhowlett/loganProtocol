
# **Project ORION – The Agentic Orchestration Engine**  
*An AI-driven protocol for autonomous agent collaboration and orchestration*

[![License](https://img.shields.io/github/license/L0ganhowlett/Project-ORION)](LICENSE)
[![AWS](https://img.shields.io/badge/Built_on-AWS_Bedrock-orange)](#)
[![Java](https://img.shields.io/badge/Backend-Java_21_&_SpringBoot-blue)](#)
[![LLM](https://img.shields.io/badge/LLM-Nova_Bedrock-green)](#)
[![Status](https://img.shields.io/badge/Status-Active-success)](#)

---

## **Inspiration**

> *“What if AI agents could collaborate intelligently, like a digital team — planning, reasoning, and acting together?”*

Modern LLMs can *think* but not *coordinate*. Traditional APIs work in silos — unable to reason or collaborate.  
**Project ORION** bridges this gap with an **agentic orchestration protocol**, where AI agents **plan, reason, and act collaboratively**, pausing when human context is needed and resuming autonomously.

 **LLM Orchestrator = Brain**  
 **Agents = Muscles**  
 **Kernel = Nervous System**

---

##  **Overview**

**ORION** is an **Agentic AI Protocol** that transforms microservices into autonomous reasoning agents.  
Each agent can register, discover, and communicate dynamically through a central **Kernel**, forming a self-governing **multi-agent ecosystem** powered by **AWS Bedrock**.

---

##  **Key Features**

-  **Custom AI Agent Registration** — Convert any REST/ML service into an agent  
-  **Automatic Orchestration** — Dynamic reasoning and action planning via Bedrock  
-  **Human-in-the-loop** — Intelligent pauses for user input, automatic resume  
-  **Event-driven Kernel** — Asynchronous orchestration with `CompletableFuture`  
-  **Real-time Visualization** — Live orchestration dashboard via SSE  
-  **Persistent State** — Session memory with JSON-based resumption  

---

## ️ **Architecture Overview**

![ORION Architecture](https://raw.githubusercontent.com/L0ganhowlett/loganProtocol/master/Architecture.png?update=2)

| Layer | Description |
|-------|--------------|
| **Eureka Discovery** | Dynamic registry for all agent microservices |
| **Kernel Service** | The orchestration core — manages lifecycle, Redis cache, MySQL persistence |
| **Orchestrator-Bedrock-Agent** | Brain that uses AWS Bedrock/Nova to reason and delegate |
| **N-Bedrock-Agents** | Specialized AI agents for tasks (Billing, Jenkins, Validation, etc.) |
| **Use Case Microservices** | Real-world systems wrapped as agents (e.g., MSME Billing, Jenkins CI/CD) |
| **UI Layer** | Real-time orchestration dashboard hosted on EC2 |
| **RDS (MySQL)** | Persistent state, orchestration logs, and agent metadata |

---

## ️ **AWS Infrastructure**

| Component | Purpose | AWS Service |
|------------|----------|--------------|
| EC2 | Hosts kernel, orchestrator, agents, and UI | Amazon EC2 |
| VPC | Isolated secure inter-agent communication | Amazon VPC |
| Redis | Caching and pub/sub orchestration | Amazon Elasticache |
| Bedrock | Reasoning and decision-making layer | AWS Bedrock |
| MySQL | Persistent metadata and session state | Amazon RDS |
| IAM | Bedrock and S3 permissions | AWS IAM |
| CloudWatch | Logs, health metrics, and orchestration insights | Amazon CloudWatch |

---

##  **Data Flow**

1. **Startup:** Agents self-register with Eureka → Kernel initializes orchestration.  
2. **User Request:** User triggers a workflow via UI → Kernel delegates to Orchestrator.  
3. **Reasoning:** Bedrock model plans agent sequence → Delegates to N-Bedrock agents.  
4. **Execution:** Agents execute, update Kernel → Kernel updates MySQL & Redis.  
5. **UI Update:** Real-time dashboard displays orchestration flow and agent logs.

---

##  **Example: MSME Invoice Workflow**

**Prompt:**  
> “Create a new invoice for vendor *‘Zenith Retailers’* with amount ₹45,000 and notify the vendor.”

**Agentic Sequence:**

| Agent | Action | Tool |
|--------|---------|------|
|  Invoice Agent | Creates new invoice | `Create_Invoice_Tool(vendor, amount)` |
|  Notification Agent | Notifies vendor | `Send_Notification_Tool(vendor, message)` |

Outcome:  
Invoice `INV-1042` created, validated, and vendor notified — autonomously.

---

##  **Jenkins Agentic Deployer Protocol**

Enables **autonomous CI/CD orchestration** using agents for build, monitoring, and deployment.

| Agent                 | Tool Example | Description |
|-----------------------|---------------|-------------|
| Jenkins Agent         | `Trigger_Job_Tool(jobName, parameters)` | Starts a Jenkins build |
| ️ Build Monitor Agent | `Monitor_Build_Tool(jobName)` | Tracks build progress |
| Deployment Agent      | `Deploy_Build_Tool(jobName, env)` | Deploys to staging/production |
| Notification Agent    | `Send_Build_Notification_Tool(jobName, status, message)` | Sends alerts on success/failure |

 Result: Fully automated CI/CD pipelines orchestrated by reasoning agents.

---

##  **Live Demos**

| UI | URL | Credentials |
|----|-----|--------------|
| **Agentic AI UI** | [View Dashboard](http://ec2-13-233-77-128.ap-south-1.compute.amazonaws.com:5173) | viewer / jenkins123 |
| **MSME UI** | [Launch MSME UI](http://ec2-13-233-77-128.ap-south-1.compute.amazonaws.com:5174) | viewer / jenkins123 |
| **Jenkins UI** | [Launch Jenkins UI](http://ec2-13-233-77-128.ap-south-1.compute.amazonaws.com:5175) | viewer / jenkins123 |

---

##  **Core Philosophy**

> “You don’t just automate workflows —  
> you build *thinking systems* that evolve with your goals.”

**Project ORION** represents the next step in LLM application design — not prompt-response systems, but **cooperative AI networks** that reason, plan, and act.

---

##  **Maintainer**

**Adwait Laud ([@L0ganhowlett](https://github.com/L0ganhowlett))**  
*AI Systems Engineer • AWS Agentic Protocols Researcher*

---

##  **License**

This project is licensed under the [MIT License](LICENSE).
