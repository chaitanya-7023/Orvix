import React, { useState, useEffect, useRef } from "react";
import { ProjectExplorer, FileNode } from "./components/ProjectExplorer";
import { CodeEditor, Diagnostic } from "./components/CodeEditor";
import { AIAssistant } from "./components/AIAssistant";
import { ConsolePanel } from "./components/ConsolePanel";
import { Play, Square, Save, RotateCw, Moon, Sun, ArrowLeft, GitBranch, Terminal as ConsoleIcon, ExternalLink, Settings } from "lucide-react";

interface OpenFile {
  path: string;
  content: string;
  isDirty: boolean;
}

const IMPORT_STAGES = [
  "URL Validation",
  "Cloning Repository",
  "Analyzing Repository",
  "Building Project Structure",
  "Loading Files",
  "Generating AI Insights",
  "Opening Workspace"
];

export default function App() {
  // Theme & Workspace Selection
  const [isDarkTheme, setIsDarkTheme] = useState(true);
  const [projectName, setProjectName] = useState<string | null>(null);
  const [projectList, setProjectList] = useState<string[]>([]);
  
  // Import Flow State
  const [importUrl, setImportUrl] = useState("");
  const [importing, setImporting] = useState(false);
  const [currentImportStage, setCurrentImportStage] = useState("");
  const [importProgressText, setImportProgressText] = useState("");

  // Editor and Filesystem State
  const [fileTree, setFileTree] = useState<FileNode | null>(null);
  const [openFiles, setOpenFiles] = useState<OpenFile[]>([]);
  const [activeFilePath, setActiveFilePath] = useState<string | null>(null);
  const [diagnostics, setDiagnostics] = useState<Diagnostic[]>([]);
  
  // Execution & Logs
  const [logs, setLogs] = useState<string[]>([]);
  const [isRunning, setIsRunning] = useState(false);
  const eventSourceRef = useRef<EventSource | null>(null);

  // Settings & Customizations (Feature 4)
  const [editorFontSize, setEditorFontSize] = useState(13);
  const [editorZoom, setEditorZoom] = useState(100);
  const [autoSaveInterval, setAutoSaveInterval] = useState(2000);
  const [showSettingsModal, setShowSettingsModal] = useState(false);

  // Active Editor Selection Context (Interaction Principles)
  const [selectedText, setSelectedText] = useState("");
  const [cursorLine, setCursorLine] = useState(1);
  const [cursorCol, setCursorCol] = useState(1);

  // Automatic Runtime Exception Analysis (Feature 10)
  const [runtimeCrash, setRuntimeCrash] = useState<any | null>(null);

  // Monaco editor and auto-save timer refs
  const editorRef = useRef<any>(null);
  const autoSaveTimerRef = useRef<any>(null);

  // Initialize
  useEffect(() => {
    fetchProjects();
    // Toggle body dark mode class
    if (isDarkTheme) {
      document.documentElement.classList.add("dark");
    } else {
      document.documentElement.classList.remove("dark");
    }
  }, [isDarkTheme]);

  // Fetch list of local projects
  const fetchProjects = async () => {
    try {
      const res = await fetch("http://localhost:8080/api/projects");
      if (res.ok) {
        const list = await res.json();
        setProjectList(list);
      }
    } catch (e) {
      console.error("Failed to connect to backend api.");
    }
  };

  // Start repository import via SSE stream
  const handleImport = (e: React.FormEvent) => {
    e.preventDefault();
    if (!importUrl.trim()) return;

    setImporting(true);
    setCurrentImportStage(IMPORT_STAGES[0]);
    setImportProgressText("Starting import...");

    const source = new EventSource(`http://localhost:8080/api/projects/import?url=${encodeURIComponent(importUrl)}`);
    
    source.addEventListener("progress", (event: any) => {
      try {
        const data = JSON.parse(event.data);
        setCurrentImportStage(data.stage);
        setImportProgressText(data.details);
      } catch (err) {
        setImportProgressText(event.data);
      }
    });

    source.addEventListener("error", (event: any) => {
      setImportProgressText("Error: " + (event.data || "Could not complete import. Is repository public?"));
      source.close();
      setImporting(false);
      fetchProjects();
    });

    source.addEventListener("complete", (event: any) => {
      const importedName = event.data;
      source.close();
      setImporting(false);
      setProjectName(importedName);
      loadProjectTree(importedName);
      fetchProjects();
    });
  };

  // Load project files tree
  const loadProjectTree = async (name: string) => {
    try {
      const res = await fetch(`http://localhost:8080/api/projects/${name}/tree`);
      if (res.ok) {
        const tree = await res.json();
        setFileTree(tree);
      }
    } catch (e) {
      console.error("Failed to load project tree.");
    }
  };

  // Load a file's content on select
  const handleFileSelect = async (path: string) => {
    const existing = openFiles.find((f) => f.path === path);
    if (existing) {
      setActiveFilePath(path);
      triggerDiagnostics(path, existing.content);
      return;
    }

    try {
      const res = await fetch(`http://localhost:8080/api/projects/${projectName}/files?path=${encodeURIComponent(path)}`);
      if (res.ok) {
        const content = await res.text();
        const newFile = { path, content, isDirty: false };
        setOpenFiles((prev) => [...prev, newFile]);
        setActiveFilePath(path);
        triggerDiagnostics(path, content);
      }
    } catch (e) {
      console.error("Failed to load file contents.");
    }
  };

  // Track code edits and run real-time diagnostics (with debounced auto-save)
  const handleFileChange = (path: string, newContent: string) => {
    setOpenFiles((prev) =>
      prev.map((f) => (f.path === path ? { ...f, content: newContent, isDirty: true } : f))
    );
    triggerDiagnostics(path, newContent);

    if (autoSaveTimerRef.current) {
      clearTimeout(autoSaveTimerRef.current);
    }
    autoSaveTimerRef.current = setTimeout(async () => {
      try {
        await fetch(`http://localhost:8080/api/projects/${projectName}/files?path=${encodeURIComponent(path)}`, {
          method: "POST",
          headers: { "Content-Type": "text/plain" },
          body: newContent,
        });
        setOpenFiles((prev) =>
          prev.map((f) => (f.path === path ? { ...f, isDirty: false } : f))
        );
      } catch (e) {
        console.error("Auto-save failed:", e);
      }
    }, autoSaveInterval);
  };

  const handleNavigate = (filePath: string, line: number) => {
    handleFileSelect(filePath).then(() => {
      setTimeout(() => {
        if (editorRef.current) {
          editorRef.current.revealLineInCenter(line);
          editorRef.current.setPosition({ lineNumber: line, column: 1 });
          editorRef.current.focus();
        }
      }, 300);
    });
  };

  const handleMoveNode = async (path: string, targetDir: string) => {
    try {
      const res = await fetch(
        `http://localhost:8080/api/projects/${projectName}/move?path=${encodeURIComponent(path)}&targetDir=${encodeURIComponent(targetDir)}`,
        { method: "POST" }
      );
      if (res.ok) {
        loadProjectTree(projectName!);
      } else {
        alert("Move failed. Ensure target directory exists.");
      }
    } catch (e) {
      alert("Error moving file.");
    }
  };

  const handleSelectionChange = (text: string, line: number, col: number) => {
    setSelectedText(text);
    setCursorLine(line);
    setCursorCol(col);
  };

  // Debounced/direct compilation checks
  const triggerDiagnostics = async (path: string, content: string) => {
    if (!path.endsWith(".java")) {
      setDiagnostics([]);
      return;
    }
    try {
      const res = await fetch(`http://localhost:8080/api/projects/${projectName}/diagnose?path=${encodeURIComponent(path)}`, {
        method: "POST",
        headers: { "Content-Type": "text/plain" },
        body: content,
      });
      if (res.ok) {
        const markers = await res.json();
        setDiagnostics(markers);
      }
    } catch (e) {
      console.error("Diagnostics request failed.");
    }
  };

  // Close editor tab
  const handleFileClose = (path: string) => {
    const remaining = openFiles.filter((f) => f.path !== path);
    setOpenFiles(remaining);
    if (activeFilePath === path) {
      setActiveFilePath(remaining.length > 0 ? remaining[remaining.length - 1].path : null);
    }
  };

  // Save changes to disk
  const handleSaveActiveFile = async () => {
    const activeFile = openFiles.find((f) => f.path === activeFilePath);
    if (!activeFile || !projectName) return;

    try {
      const res = await fetch(`http://localhost:8080/api/projects/${projectName}/files?path=${encodeURIComponent(activeFilePath!)}`, {
        method: "POST",
        headers: { "Content-Type": "text/plain" },
        body: activeFile.content,
      });

      if (res.ok) {
        setOpenFiles((prev) =>
          prev.map((f) => (f.path === activeFilePath ? { ...f, isDirty: false } : f))
        );
      } else {
        alert("Failed to save file.");
      }
    } catch (e) {
      alert("Error saving file: " + e);
    }
  };

  // Apply code fix directly into the editor
  const handleApplyFix = (proposedCode: string, originalCode: string) => {
    const activeFile = openFiles.find((f) => f.path === activeFilePath);
    if (!activeFile) return;

    let newContent = activeFile.content;
    if (newContent.includes(originalCode)) {
      newContent = newContent.replace(originalCode, proposedCode);
    } else {
      // Fallback: replace lines containing matching snippets or append to end if conflict
      alert("Direct text replacement failed due to editor mismatch. Appending proposed solution.");
      newContent = newContent + "\n\n" + proposedCode;
    }

    handleFileChange(activeFilePath!, newContent);
  };

  // File explorer operations
  const handleCreateNode = async (path: string, isFolder: boolean) => {
    try {
      const res = await fetch(
        `http://localhost:8080/api/projects/${projectName}/create?path=${encodeURIComponent(path)}&isFolder=${isFolder}`,
        { method: "POST" }
      );
      if (res.ok) {
        loadProjectTree(projectName!);
      }
    } catch (e) {
      alert("Failed to create file/folder.");
    }
  };

  const handleDeleteNode = async (path: string) => {
    try {
      const res = await fetch(
        `http://localhost:8080/api/projects/${projectName}/delete?path=${encodeURIComponent(path)}`,
        { method: "DELETE" }
      );
      if (res.ok) {
        handleFileClose(path);
        loadProjectTree(projectName!);
      }
    } catch (e) {
      alert("Failed to delete file/folder.");
    }
  };

  const handleRenameNode = async (path: string, newName: string) => {
    try {
      const res = await fetch(
        `http://localhost:8080/api/projects/${projectName}/rename?path=${encodeURIComponent(path)}&newName=${encodeURIComponent(newName)}`,
        { method: "POST" }
      );
      if (res.ok) {
        loadProjectTree(projectName!);
        // If the renamed file is open, update its path
        setOpenFiles((prev) =>
          prev.map((f) => {
            if (f.path === path) {
              const dir = path.substring(0, path.lastIndexOf("/") + 1);
              return { ...f, path: dir + newName };
            }
            return f;
          })
        );
      }
    } catch (e) {
      alert("Failed to rename file/folder.");
    }
  };

  // Compile and run project
  const handleRunProject = () => {
    if (!projectName) return;
    setIsRunning(true);
    setLogs([]);
    setRuntimeCrash(null);

    // Open connection
    const source = new EventSource(`http://localhost:8080/api/projects/${projectName}/run`);
    eventSourceRef.current = source;

    source.addEventListener("console", (event: any) => {
      setLogs((prev) => [...prev, event.data]);
    });

    source.addEventListener("system", (event: any) => {
      setLogs((prev) => [...prev, `System: ${event.data}`]);
    });

    source.addEventListener("runtime-error-analysis", (event: any) => {
      try {
        const data = JSON.parse(event.data);
        setRuntimeCrash(data);
      } catch (err) {
        console.error("Failed to parse runtime error details:", err);
      }
    });

    source.onerror = () => {
      source.close();
      setIsRunning(false);
    };

    source.addEventListener("complete", () => {
      source.close();
      setIsRunning(false);
    });
  };

  const handleStopProject = async () => {
    if (!projectName) return;
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
    }
    try {
      await fetch(`http://localhost:8080/api/projects/${projectName}/stop`, { method: "POST" });
      setLogs((prev) => [...prev, "System: Application stopped by user."]);
    } catch (e) {
      // ignore
    }
    setIsRunning(false);
  };

  const getActiveFileContent = () => {
    const file = openFiles.find((f) => f.path === activeFilePath);
    return file ? file.content : null;
  };

  // Keyboard shortcut listener for Ctrl+S
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === "s") {
        e.preventDefault();
        handleSaveActiveFile();
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [activeFilePath, openFiles]);

  // Project Dashboard / Landing page if no project open
  if (!projectName) {
    return (
      <div className="min-h-screen bg-background text-foreground flex flex-col justify-between select-none">
        {/* Navigation */}
        <header className="flex justify-between items-center p-5 border-b border-border bg-card">
          <div className="flex items-center space-x-2">
            <span className="font-bold text-xl text-primary tracking-tight">DevFlow</span>
            <span className="text-[10px] bg-primary/10 text-primary px-1.5 py-0.5 rounded font-mono">MVP</span>
          </div>
          <button
            onClick={() => setIsDarkTheme(!isDarkTheme)}
            className="p-2 bg-muted hover:bg-muted/80 rounded-md transition"
          >
            {isDarkTheme ? <Sun size={16} /> : <Moon size={16} />}
          </button>
        </header>

        {/* Dashboard Landing Body */}
        <main className="flex-1 max-w-4xl mx-auto w-full px-5 py-12 flex flex-col justify-center items-center">
          <div className="text-center max-w-lg mb-10 space-y-3">
            <h1 className="text-4xl font-extrabold tracking-tight">Import. Build. Debug. Fix.</h1>
            <p className="text-sm text-muted-foreground leading-relaxed">
              Paste a public GitHub Java repository to instantiate a live IDE. Write code, compiler error highlights trigger in real-time, execute projects instantly, and resolve runtime stack exceptions with Gemini.
            </p>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-8 w-full">
            {/* Import Repository Box */}
            <div className="bg-card border border-border rounded-xl p-6 space-y-4 shadow-sm">
              <div className="flex items-center space-x-2">
                <GitBranch size={20} className="text-primary" />
                <h3 className="font-bold text-base">Import from GitHub</h3>
              </div>
              <form onSubmit={handleImport} className="space-y-3">
                <input
                  type="text"
                  placeholder="https://github.com/username/project"
                  value={importUrl}
                  onChange={(e) => setImportUrl(e.target.value)}
                  disabled={importing}
                  className="w-full text-sm bg-muted/50 rounded-md px-3.5 py-2.5 border border-border focus:outline-none focus:ring-2 focus:ring-primary/40 focus:border-primary disabled:opacity-50"
                />
                <button
                  type="submit"
                  disabled={importing || !importUrl.trim()}
                  className="w-full bg-primary text-primary-foreground hover:bg-primary/95 text-sm font-semibold py-2.5 rounded-md transition disabled:opacity-50"
                >
                  {importing ? "Importing Project..." : "Import Repository"}
                </button>
              </form>

              {/* Real-time Import Progress Stages */}
              {importing && (
                <div className="bg-muted/30 border border-border rounded-lg p-4 space-y-3">
                  <div className="flex items-center justify-between text-xs font-semibold">
                    <span className="text-primary">{currentImportStage}</span>
                    <span className="text-muted-foreground">
                      {Math.round(((IMPORT_STAGES.indexOf(currentImportStage) + 1) / IMPORT_STAGES.length) * 100)}%
                    </span>
                  </div>
                  
                  {/* Progress bar indicator */}
                  <div className="w-full bg-muted h-2 rounded-full overflow-hidden">
                    <div
                      className="bg-primary h-2 transition-all duration-300"
                      style={{
                        width: `${((IMPORT_STAGES.indexOf(currentImportStage) + 1) / IMPORT_STAGES.length) * 100}%`,
                      }}
                    />
                  </div>

                  <p className="text-[11px] text-muted-foreground font-medium animate-pulse">
                    {importProgressText}
                  </p>
                </div>
              )}
            </div>

            {/* Select Local Projects Box */}
            <div className="bg-card border border-border rounded-xl p-6 space-y-4 shadow-sm flex flex-col">
              <h3 className="font-bold text-base flex items-center space-x-2">
                <ConsoleIcon size={20} className="text-primary" />
                <span>Existing Workspaces</span>
              </h3>
              <div className="flex-1 min-h-[140px] overflow-y-auto space-y-2 border border-border rounded-lg p-2.5 bg-muted/10">
                {projectList.length === 0 ? (
                  <div className="text-xs text-muted-foreground italic text-center pt-8">
                    No active workspaces found
                  </div>
                ) : (
                  projectList.map((proj) => (
                    <button
                      key={proj}
                      onClick={() => {
                        setProjectName(proj);
                        loadProjectTree(proj);
                      }}
                      className="w-full flex items-center justify-between p-2 hover:bg-muted rounded text-left text-sm font-medium transition"
                    >
                      <span className="truncate">{proj}</span>
                      <ExternalLink size={13} className="text-muted-foreground" />
                    </button>
                  ))
                )}
              </div>
            </div>
          </div>
        </main>

        <footer className="p-5 border-t border-border text-center text-xs text-muted-foreground bg-card select-none">
          DevFlow MVP IDE © 2026. Made with React, Spring Boot, JGit & Gemini API.
        </footer>
      </div>
    );
  }

  // Active Workspace IDE view
  return (
    <div className="h-screen bg-background text-foreground flex flex-col select-none overflow-hidden">
      {/* Workspace Menu Bar */}
      <header className="flex justify-between items-center px-4 py-2 border-b border-border bg-card">
        <div className="flex items-center space-x-4">
          <button
            onClick={() => {
              setProjectName(null);
              setOpenFiles([]);
              setActiveFilePath(null);
              setDiagnostics([]);
              setLogs([]);
              handleStopProject();
            }}
            className="flex items-center space-x-1.5 text-xs text-muted-foreground hover:text-foreground font-medium p-1 hover:bg-muted rounded transition"
          >
            <ArrowLeft size={13} />
            <span>Dashboard</span>
          </button>
          
          <div className="h-4 w-[1px] bg-border" />
          
          <div className="flex items-center space-x-2">
            <span className="font-bold text-sm text-primary">{projectName}</span>
            <span className="text-[10px] bg-emerald-500/10 text-emerald-500 font-bold px-1.5 py-0.5 rounded">
              Active
            </span>
          </div>
        </div>

        {/* Action Controls */}
        <div className="flex items-center space-x-2">
          <button
            onClick={handleSaveActiveFile}
            disabled={!activeFilePath}
            className="flex items-center space-x-1 bg-muted hover:bg-muted-foreground/20 px-2.5 py-1.5 text-xs font-semibold rounded disabled:opacity-40 transition"
          >
            <Save size={13} />
            <span>Save (Ctrl+S)</span>
          </button>
          <button
            onClick={() => loadProjectTree(projectName)}
            className="flex items-center space-x-1 bg-muted hover:bg-muted-foreground/20 px-2.5 py-1.5 text-xs font-semibold rounded transition"
          >
            <RotateCw size={13} />
            <span>Refresh Explorer</span>
          </button>

          <div className="h-4 w-[1px] bg-border mx-1" />

          {isRunning ? (
            <button
              onClick={handleStopProject}
              className="flex items-center space-x-1 bg-destructive text-destructive-foreground hover:bg-destructive/90 px-3 py-1.5 text-xs font-semibold rounded shadow-sm transition"
            >
              <Square size={13} />
              <span>Stop</span>
            </button>
          ) : (
            <button
              onClick={handleRunProject}
              className="flex items-center space-x-1 bg-primary text-primary-foreground hover:bg-primary/95 px-3 py-1.5 text-xs font-semibold rounded shadow-sm transition"
            >
              <Play size={13} />
              <span>Run</span>
            </button>
          )}

          <button
            onClick={() => setIsDarkTheme(!isDarkTheme)}
            className="p-1.5 bg-muted hover:bg-muted-foreground/20 rounded transition ml-2"
          >
            {isDarkTheme ? <Sun size={14} /> : <Moon size={14} />}
          </button>

          <button
            onClick={() => setShowSettingsModal(true)}
            className="p-1.5 bg-muted hover:bg-muted-foreground/20 rounded transition ml-2"
            title="Settings"
          >
            <Settings size={14} />
          </button>
        </div>
      </header>

      {/* Main Panel Content Area */}
      <div className="flex-1 flex overflow-hidden">
        {/* Left Explorer (Fixed Width) */}
        <div className="w-60 flex-shrink-0 h-full">
          <ProjectExplorer
            projectName={projectName}
            fileTree={fileTree}
            onFileSelect={handleFileSelect}
            selectedPath={activeFilePath}
            onRefresh={() => loadProjectTree(projectName)}
            onCreateNode={handleCreateNode}
            onDeleteNode={handleDeleteNode}
            onRenameNode={handleRenameNode}
            onMoveNode={handleMoveNode}
          />
        </div>

        {/* Center Editor & Bottom Console (Split Panel) */}
        <div className="flex-1 flex flex-col overflow-hidden">
          <div className="flex-1 min-h-0">
            <CodeEditor
              openFiles={openFiles}
              activeFilePath={activeFilePath}
              onFileChange={handleFileChange}
              onFileClose={handleFileClose}
              onTabSelect={handleFileSelect}
              diagnostics={diagnostics}
              isDarkTheme={isDarkTheme}
              fontSize={Math.round(editorFontSize * (editorZoom / 100))}
              onSelectionChange={handleSelectionChange}
              editorRef={editorRef}
            />
          </div>
          <div className="h-44 flex-shrink-0">
            <ConsolePanel logs={logs} onClearLogs={() => setLogs([])} />
          </div>
        </div>

        {/* Right Assistant (Fixed Width) */}
        <div className="w-80 flex-shrink-0 h-full">
          <AIAssistant
            projectName={projectName}
            activeFilePath={activeFilePath}
            activeFileContent={getActiveFileContent()}
            diagnostics={diagnostics}
            onApplyFix={handleApplyFix}
            isDarkTheme={isDarkTheme}
            onNavigate={handleNavigate}
            selectedText={selectedText}
            cursorLine={cursorLine}
            cursorCol={cursorCol}
          />
        </div>
      </div>

      {/* Settings Modal (Feature 4) */}
      {showSettingsModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm select-none">
          <div className="bg-card border border-border rounded-xl w-full max-w-sm shadow-2xl p-5 space-y-4">
            <div className="flex items-center justify-between border-b border-border pb-3">
              <h3 className="font-bold text-sm tracking-wide uppercase text-muted-foreground">Editor Settings</h3>
              <button onClick={() => setShowSettingsModal(false)} className="text-muted-foreground hover:text-foreground text-sm font-semibold">×</button>
            </div>
            <div className="space-y-4 text-xs">
              <div className="space-y-1.5 flex flex-col">
                <label className="text-muted-foreground font-semibold">Font Size ({editorFontSize}px)</label>
                <input
                  type="range"
                  min="10"
                  max="24"
                  value={editorFontSize}
                  onChange={(e) => setEditorFontSize(parseInt(e.target.value))}
                  className="w-full h-1 bg-muted rounded-lg appearance-none cursor-pointer accent-primary"
                />
              </div>
              <div className="space-y-1.5 flex flex-col">
                <label className="text-muted-foreground font-semibold">Zoom Level ({editorZoom}%)</label>
                <input
                  type="range"
                  min="50"
                  max="150"
                  step="10"
                  value={editorZoom}
                  onChange={(e) => setEditorZoom(parseInt(e.target.value))}
                  className="w-full h-1 bg-muted rounded-lg appearance-none cursor-pointer accent-primary"
                />
              </div>
              <div className="space-y-1.5 flex flex-col">
                <label className="text-muted-foreground font-semibold">Auto-Save Debounce Interval</label>
                <select
                  value={autoSaveInterval}
                  onChange={(e) => setAutoSaveInterval(parseInt(e.target.value))}
                  className="w-full bg-muted border border-border rounded px-2.5 py-1.5 focus:outline-none focus:ring-1 focus:ring-primary"
                >
                  <option value="500">500ms</option>
                  <option value="1000">1000ms (1s)</option>
                  <option value="2000">2000ms (2s)</option>
                  <option value="5000">5000ms (5s)</option>
                </select>
              </div>
            </div>
            <div className="flex justify-end pt-3 border-t border-border">
              <button
                onClick={() => setShowSettingsModal(false)}
                className="bg-primary text-primary-foreground hover:bg-primary/95 text-xs font-semibold px-4 py-2 rounded transition"
              >
                Save & Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Runtime Error Analyzer Dialog (Feature 10) */}
      {runtimeCrash && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4 select-none">
          <div className="bg-card border border-destructive/30 rounded-xl w-full max-w-md shadow-2xl p-5 space-y-4">
            <div className="flex items-center space-x-2 text-destructive border-b border-border pb-3">
              <AlertTriangle size={18} />
              <h3 className="font-bold text-sm tracking-wide uppercase">Runtime Exception Detected</h3>
            </div>
            
            <div className="space-y-2.5 text-xs select-text">
              <div>
                <span className="text-muted-foreground font-bold uppercase tracking-wider block text-[10px]">Root Cause</span>
                <p className="font-medium text-foreground">{runtimeCrash.rootCause}</p>
              </div>
              <div className="flex space-x-4">
                <div>
                  <span className="text-muted-foreground font-bold uppercase tracking-wider block text-[10px]">File</span>
                  <p 
                    className="font-semibold text-blue-500 dark:text-blue-400 hover:underline cursor-pointer" 
                    onClick={() => { handleNavigate(runtimeCrash.affectedFile, runtimeCrash.line); setRuntimeCrash(null); }}
                  >
                    {runtimeCrash.affectedFile.substring(runtimeCrash.affectedFile.lastIndexOf("/") + 1)}
                  </p>
                </div>
                <div>
                  <span className="text-muted-foreground font-bold uppercase tracking-wider block text-[10px]">Line</span>
                  <p className="font-semibold text-foreground">{runtimeCrash.line}</p>
                </div>
                <div>
                  <span className="text-muted-foreground font-bold uppercase tracking-wider block text-[10px]">Confidence</span>
                  <p className="font-semibold text-foreground">{runtimeCrash.confidenceScore}%</p>
                </div>
              </div>
              <div>
                <span className="text-muted-foreground font-bold uppercase tracking-wider block text-[10px]">Impact</span>
                <p className="text-foreground/90 leading-relaxed">{runtimeCrash.impact}</p>
              </div>
              <div className="bg-destructive/5 border border-destructive/20 rounded p-2.5">
                <span className="text-destructive font-bold uppercase tracking-wider block text-[10px] mb-0.5">AI Fix Recommendation</span>
                <p className="text-foreground/90 leading-relaxed italic">{runtimeCrash.fixRecommendation}</p>
              </div>
            </div>

            <div className="flex justify-end space-x-2 pt-3 border-t border-border select-none">
              <button
                onClick={() => setRuntimeCrash(null)}
                className="bg-muted hover:bg-muted-foreground/20 text-xs font-semibold px-4 py-2 rounded transition"
              >
                Dismiss
              </button>
              <button
                onClick={() => {
                  handleNavigate(runtimeCrash.affectedFile, runtimeCrash.line);
                  setRuntimeCrash(null);
                }}
                className="bg-primary text-primary-foreground hover:bg-primary/95 text-xs font-semibold px-4 py-2 rounded transition shadow-sm"
              >
                Jump to Error
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
