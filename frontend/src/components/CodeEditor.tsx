import React, { useEffect, useRef } from "react";
import MonacoEditor, { Monaco } from "@monaco-editor/react";

export interface Diagnostic {
  line: number;
  column: number;
  severity: string;
  severityLabel: string;
  confidence: number;
  message: string;
  code: string;
}

interface OpenFile {
  path: string;
  content: string;
  isDirty: boolean;
}

interface CodeEditorProps {
  openFiles: OpenFile[];
  activeFilePath: string | null;
  onFileChange: (path: string, content: string) => void;
  onFileClose: (path: string) => void;
  onTabSelect: (path: string) => void;
  diagnostics: Diagnostic[];
  isDarkTheme: boolean;
  fontSize: number;
  onSelectionChange: (selectedText: string, line: number, column: number) => void;
  editorRef: React.MutableRefObject<any>;
}

export const CodeEditor: React.FC<CodeEditorProps> = ({
  openFiles,
  activeFilePath,
  onFileChange,
  onFileClose,
  onTabSelect,
  diagnostics,
  isDarkTheme,
  fontSize,
  onSelectionChange,
  editorRef,
}) => {
  const activeFile = openFiles.find((f) => f.path === activeFilePath);
  const monacoRef = useRef<Monaco | null>(null);

  const handleEditorDidMount = (editor: any, monaco: Monaco) => {
    editorRef.current = editor;
    monacoRef.current = monaco;
    applyDiagnostics();

    // Listen to cursor position and selection changes
    editor.onDidChangeCursorPosition((e: any) => {
      const position = e.position;
      const selection = editor.getSelection();
      const model = editor.getModel();
      let selectedText = "";
      if (model && selection) {
        selectedText = model.getValueInRange(selection);
      }
      onSelectionChange(selectedText, position.lineNumber, position.column);
    });
  };

  const applyDiagnostics = () => {
    if (!monacoRef.current || !editorRef.current || !activeFile) return;

    const monaco = monacoRef.current;
    const model = editorRef.current.getModel();
    if (!model) return;

    // Convert our diagnostics to Monaco markers
    const markers = diagnostics.map((d) => {
      const severity = d.severity === "ERROR" 
        ? monaco.MarkerSeverity.Error 
        : monaco.MarkerSeverity.Warning;

      // Ensure valid range
      const line = Math.max(1, d.line);
      const col = Math.max(1, d.column);

      // Render custom hover matching the PRD format
      const formattedMessage = `Line ${line} — "${d.message}" — Severity: ${d.severityLabel} — Confidence: ${d.confidence}%`;

      return {
        startLineNumber: line,
        startColumn: col,
        endLineNumber: line,
        endColumn: col + 6,
        message: formattedMessage,
        severity: severity,
      };
    });

    monaco.editor.setModelMarkers(model, "devflow-diagnostics", markers);
  };

  // Update markers when active file or diagnostics change
  useEffect(() => {
    if (editorRef.current && monacoRef.current) {
      applyDiagnostics();
    }
  }, [diagnostics, activeFilePath]);

  const detectLanguage = (path: string) => {
    if (path.endsWith(".java")) return "java";
    if (path.endsWith(".xml")) return "xml";
    if (path.endsWith(".json")) return "json";
    if (path.endsWith(".md")) return "markdown";
    if (path.endsWith(".js") || path.endsWith(".jsx")) return "javascript";
    if (path.endsWith(".ts") || path.endsWith(".tsx")) return "typescript";
    if (path.endsWith(".css")) return "css";
    return "plaintext";
  };

  return (
    <div className="flex flex-col h-full bg-background select-none">
      {/* File Tabs */}
      <div className="flex bg-muted/30 border-b border-border overflow-x-auto min-h-9 select-none">
        {openFiles.map((file) => {
          const fileName = file.path.substring(file.path.lastIndexOf("/") + 1);
          const isActive = file.path === activeFilePath;
          return (
            <div
              key={file.path}
              className={`flex items-center space-x-2 px-3 py-1.5 cursor-pointer text-xs border-r border-border transition-all duration-150 ${
                isActive
                  ? "bg-card text-foreground font-medium border-t-2 border-t-primary"
                  : "bg-muted/15 text-muted-foreground hover:bg-muted/40 hover:text-foreground"
              }`}
              onClick={() => onTabSelect(file.path)}
            >
              <span className="truncate max-w-[120px]">{fileName}</span>
              {file.isDirty && (
                <span className="w-1.5 h-1.5 bg-primary rounded-full" title="Unsaved changes" />
              )}
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  onFileClose(file.path);
                }}
                className="hover:bg-muted rounded px-1 text-muted-foreground hover:text-foreground"
              >
                ×
              </button>
            </div>
          );
        })}
      </div>

      {/* Editor Surface */}
      <div className="flex-1 min-h-0 relative">
        {activeFile ? (
          <MonacoEditor
            height="100%"
            language={detectLanguage(activeFile.path)}
            theme={isDarkTheme ? "vs-dark" : "light"}
            value={activeFile.content}
            onChange={(val) => onFileChange(activeFile.path, val || "")}
            onMount={handleEditorDidMount}
            options={{
              minimap: { enabled: true },
              fontSize: fontSize,
              automaticLayout: true,
              scrollBeyondLastLine: false,
              wordWrap: "on",
              tabSize: 4,
              lineNumbers: "on",
              cursorBlinking: "smooth",
            }}
          />
        ) : (
          <div className="absolute inset-0 flex flex-col items-center justify-center text-muted-foreground">
            <svg
              className="w-16 h-16 opacity-20 mb-3"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
              xmlns="http://www.w3.org/2000/svg"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={1.5}
                d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253"
              />
            </svg>
            <p className="text-sm font-medium">Select a file from the explorer to edit</p>
          </div>
        )}
      </div>
    </div>
  );
};
