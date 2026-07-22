import React, { useRef, useEffect } from "react";
import { Terminal, Trash2 } from "lucide-react";

interface ConsolePanelProps {
  logs: string[];
  onClearLogs: () => void;
}

export const ConsolePanel: React.FC<ConsolePanelProps> = ({ logs, onClearLogs }) => {
  const consoleEndRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    consoleEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [logs]);

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
      {/* Console Header */}
      <div className="flex items-center justify-between px-4 py-2 border-b border-border bg-[#09090b] select-none">
        <div className="flex items-center space-x-2 text-muted-foreground">
          <Terminal size={14} />
          <span className="font-semibold text-[11px] uppercase tracking-wider">Console Output</span>
        </div>
        <button
          onClick={onClearLogs}
          title="Clear Console"
          className="p-1 hover:bg-muted/30 rounded text-muted-foreground hover:text-slate-200 transition"
        >
          <Trash2 size={13} />
        </button>
      </div>

      {/* Log list */}
      <div className="flex-1 overflow-y-auto p-4 space-y-1">
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
    </div>
  );
};
