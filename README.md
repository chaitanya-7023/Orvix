# DevFlow

DevFlow is an AI-powered, browser-based development environment that unifies Git importing, code editing, compilation/execution, and AI-assisted debugging into a single unified workspace.

## Project Structure

```text
devflow/
├── backend/            # Spring Boot backend (Java 26/21)
├── frontend/           # React + TypeScript frontend (Vite, Tailwind, Monaco)
└── README.md           # This file
```

## Prerequisites

- **Java**: JDK 21 or higher (detected JDK 26.0.1)
- **Node.js**: Node 18 or higher (detected v25.8.1)
- **Gemini API Key**: Set `GEMINI_API_KEY` in environment variables or a `.env` file in `backend/`.

## Running the Application

### Backend

```bash
cd backend
./mvnw spring-boot:run
```

The backend starts at `http://localhost:8080`.

### Frontend

```bash
cd frontend
npm install
npm run dev
```

The frontend starts at `http://localhost:5173`.
