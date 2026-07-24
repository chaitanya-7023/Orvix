import React, { useRef, useEffect, useState } from "react";
import { Terminal, Trash2, Globe } from "lucide-react";

interface ConsolePanelProps {
  logs: string[];
  onClearLogs: () => void;
  activePort?: number | null;
}

export const ConsolePanel: React.FC<ConsolePanelProps> = ({ logs, onClearLogs, activePort }) => {
  const consoleEndRef = useRef<HTMLDivElement | null>(null);
  const [tab, setTab] = useState<"console" | "preview">("console");

  useEffect(() => {
    consoleEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [logs]);

  // Automatically switch to console if port is cleared (app stopped)
  useEffect(() => {
    if (!activePort) {
      setTab("console");
    }
  }, [activePort]);

  const getLogStyle = (log: string) => {
    if (log.startsWith("[Build]")) {
      return "text-slate-400";
    }
    if (log.toLowerCase().includes("error") || log.toLowerCase().includes("exception") || log.toLowerCase().includes("failed")) {
      return "text-red-400 font-medium";
    }
    if (log.toLowerCase().includes("warn")) {
      return "text-amber-400";
    }
    if (log.startsWith("System:") || log.startsWith("Detected build tool:") || log.startsWith("Framework:") || log.startsWith("Entry Point:")) {
      return "text-cyan-400 font-semibold";
    }
    return "text-slate-200";
  };

  return (
    <div className="flex flex-col h-full bg-[#18181b] border-t border-border text-slate-200 font-mono text-xs select-text">
      {/* Tabbed Console Header */}
      <div className="flex items-center justify-between px-4 py-2 border-b border-border bg-[#09090b] select-none">
        <div className="flex items-center space-x-4">
          <button
            onClick={() => setTab("console")}
            className={`flex items-center space-x-1.5 py-1 px-2.5 rounded text-[11px] uppercase tracking-wider font-semibold transition ${
              tab === "console"
                ? "bg-muted/50 text-slate-200 border border-border"
                : "text-muted-foreground hover:text-slate-200"
            }`}
          >
            <Terminal size={13} />
            <span>Console Output</span>
          </button>

          {activePort && (
            <button
              onClick={() => setTab("preview")}
              className={`flex items-center space-x-1.5 py-1 px-2.5 rounded text-[11px] uppercase tracking-wider font-semibold transition ${
                tab === "preview"
                  ? "bg-muted/50 text-slate-200 border border-border"
                  : "text-muted-foreground hover:text-slate-200"
              }`}
            >
              <Globe size={13} />
              <span>Live Preview</span>
            </button>
          )}
        </div>

        {tab === "console" ? (
          <button
            onClick={onClearLogs}
            title="Clear Console"
            className="p-1 hover:bg-muted/30 rounded text-muted-foreground hover:text-slate-200 transition"
          >
            <Trash2 size={13} />
          </button>
        ) : (
          <div className="text-[10px] text-muted-foreground">
            http://localhost:{activePort}
          </div>
        )}
      </div>

      {/* Main Panel Content */}
      <div className="flex-1 overflow-hidden relative">
        {tab === "console" ? (
          <div className="w-full h-full overflow-y-auto p-4 space-y-1">
            {logs.length === 0 ? (
              <div className="text-muted-foreground italic text-center pt-4 select-none">
                No console output. Click "Run" to launch the project.
              </div>
            ) : (
              logs.map((log, i) => (
                <div key={i} className={`whitespace-pre-wrap leading-relaxed ${getLogStyle(log)}`}>
                  {log}
                </div>
              ))
            )}
            <div ref={consoleEndRef} />
          </div>
        ) : (
          <iframe
            src={`http://localhost:${activePort}`}
            className="w-full h-full border-none bg-white"
            title="Live Preview"
          />
        )}
      </div>
    </div>
  );
};
