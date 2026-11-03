# Document RAG System (Spring Boot + Spring AI + Docker)

A production-ready **Retrieval-Augmented Generation (RAG)** application built with **Java 21**, **Spring Boot**, **Spring AI**, and **PostgreSQL with PGvector**.

The application can run with either **Local LLMs via Ollama** (offline, free, private) or **Google Gemini GenAI** cloud models.

---

## 🏗️ Architecture & Technologies

- **Java Version**: 21 (Eclipse Temurin)
- **Framework**: Spring Boot 4.x with Spring AI 2.0.x
- **Vector Database**: PostgreSQL 16 with `pgvector` extension
- **Embedding Models**:
  - Ollama: `nomic-embed-text` (768 dimensions)
  - Gemini: `gemini-embedding-001` (768 dimensions)
- **Chat Models**:
  - Ollama: `qwen2.5:3b-instruct` (or any compatible model)
  - Gemini: `gemini-3.8-flash`
- **Document Processing**: `PagePdfDocumentReader` & `TokenTextSplitter` (in-memory streaming)
- **Containerization**: Multi-stage Docker build with Docker Compose

---

## 📋 Prerequisites

Before running the application, make sure you have:

### 1. Docker & Docker Compose
If you do not have Docker installed:
1. Download **Docker Desktop for Windows**: [https://www.docker.com/products/docker-desktop/](https://www.docker.com/products/docker-desktop/)
2. Run the installer and ensure **"Use WSL 2 instead of Hyper-V"** is checked.
3. Restart your computer if prompted.
4. Open Docker Desktop and wait until the status indicator in the bottom-left corner turns green (**"Engine running"**).
5. Verify in PowerShell:
   ```powershell
   docker --version
   docker compose version
   ```

### 2. Choose Your AI Provider

#### Option A: Local Models with Ollama (Default)
1. Download & install Ollama: [https://ollama.com/download](https://ollama.com/download)
2. Open PowerShell or Terminal and pull the required models:
   ```powershell
   ollama pull qwen2.5:3b-instruct
   ollama pull nomic-embed-text
   ```
3. Keep Ollama running on your host machine. The Docker container automatically connects to host Ollama using `http://host.docker.internal:11434`.

#### Option B: Google Gemini Cloud API
1. Get a free API key from [Google AI Studio](https://aistudio.google.com/).
2. You will provide this API key in your `.env` configuration file (explained below). No local models or Ollama needed!

---

## 🚀 Quick Start with Docker Compose

### Step 1: Clone the Repository
```bash
git clone https://github.com/Chandrasekhar-maker/RAG_System.git
cd RAG_System
```

### Step 2: Configure Environment Variables
Copy the example environment configuration:
```powershell
# Windows PowerShell
Copy-Item .env.example .env

# Linux / macOS
cp .env.example .env
```

Open `.env` in any text editor and adjust settings:
- **Set database password**: `DB_PASSWORD=your_secure_password`
- **If using Ollama (default)**:
  ```env
  AI_PROVIDER=ollama
  OLLAMA_BASE_URL=http://host.docker.internal:11434
  OLLAMA_CHAT_MODEL=qwen2.5:3b-instruct
  OLLAMA_EMBEDDING_MODEL=nomic-embed-text
  ```
- **If using Google Gemini**:
  ```env
  AI_PROVIDER=gemini
  GEMINI_API_KEY=AIzaSy...your_actual_key
  GEMINI_CHAT_MODEL=gemini-3.8-flash
  ```

### Step 3: Build & Start the Containers
```bash
docker compose up --build -d
```

This starts:
1. `rag-postgres`: PostgreSQL database with the `pgvector` extension on port `5432`.
2. `rag-app`: Spring Boot RAG application on port `8080`.

### Step 4: Check Logs
```bash
docker compose logs -f app
```
Look for `Started DocumentRagApplication in ... seconds`. Press `Ctrl+C` to exit logs.

---

## 📖 Using the Application

### 1. Upload a PDF Document
Upload any PDF file via `POST /upload`. The document is parsed in memory, split into semantic chunks, converted into vector embeddings, and stored in PostgreSQL:

#### Using PowerShell:
```powershell
curl.exe -X POST "http://localhost:8080/upload" -F "file=@D:\path\to\your\document.pdf"
```

#### Using cURL / Git Bash:
```bash
curl -X POST "http://localhost:8080/upload" -F "file=@/path/to/document.pdf"
```

#### Using Postman:
- Method: `POST`
- URL: `http://localhost:8080/upload`
- Body: `form-data`
- Key: `file` (type: File) -> Select your PDF
- Response: `Successfully uploaded and stored <N> chunks from document.pdf`

---

### 2. Ask Questions (RAG Query)
Query the knowledge base using the `/rag` endpoint. The system performs similarity search in `pgvector` and prompts the model to answer based strictly on the uploaded document context:

#### In your Web Browser:
```text
http://localhost:8080/rag?question=What is the main topic of the document?
```

#### Using cURL:
```bash
curl -G "http://localhost:8080/rag" --data-urlencode "question=What is the main topic of the document?"
```

---

## 💾 Data Persistence & Backup

- **PDF Documents**: PDFs are processed in memory and are **not** written to the container filesystem.
- **Vectors & Extracted Text**: Saved inside the PostgreSQL database.
- **Docker Volume**: The database uses a persistent Docker volume named `postgres_data`. Even if you stop or rebuild the containers, your indexed data remains intact.
- **Backing up vectors**:
  ```bash
  docker compose exec postgres pg_dump -U postgres postgres > backup.sql
  ```
- **Restoring vectors**:
  ```bash
  cat backup.sql | docker compose exec -T postgres psql -U postgres -d postgres
  ```

---

## 🛠️ Management Commands

| Action | Command |
|---|---|
| View application logs | `docker compose logs -f app` |
| View database logs | `docker compose logs -f postgres` |
| Stop all containers | `docker compose down` |
| Stop and wipe database data | `docker compose down -v` |
| Rebuild container after code changes | `docker compose up --build -d` |

---

## 🔒 Security Best Practices

- Never commit `.env` or real API keys to GitHub.
- The `.gitignore` and `.dockerignore` files are already configured to keep all secrets, IDE files, build targets, and logs out of version control and Docker images.
