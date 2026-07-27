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
      return "text-slate-500 font-mono";
    }
    if (log.toLowerCase().includes("error") || log.toLowerCase().includes("exception") || log.toLowerCase().includes("failed")) {
      return "text-rose-400 font-semibold border-l-2 border-rose-500 pl-3 bg-rose-500/5 my-0.5 py-0.5 rounded-r";
    }
    if (log.toLowerCase().includes("warn")) {
      return "text-amber-400 font-medium border-l-2 border-amber-500 pl-3 bg-amber-500/5 my-0.5 py-0.5 rounded-r";
    }
    if (log.startsWith("System:") || log.startsWith("Detected build tool:") || log.startsWith("Framework:") || log.startsWith("Entry Point:")) {
      return "text-indigo-400 font-bold border-l-2 border-indigo-500 pl-3 bg-indigo-500/5 my-0.5 py-0.5 rounded-r";
    }
    return "text-slate-300 font-mono";
  };

  return (
    <div className="flex flex-col h-full bg-[#030303] border-t border-white/5 text-slate-300 font-mono text-xs select-text">
      {/* Tabbed Console Header */}
      <div className="flex items-center justify-between px-5 py-2 border-b border-white/5 bg-zinc-950/70 backdrop-blur select-none">
        <div className="flex items-center space-x-3">
          <button
            onClick={() => setTab("console")}
            className={`flex items-center space-x-2 py-1 px-3 rounded-lg text-[10px] uppercase tracking-wider font-bold transition ${
              tab === "console"
                ? "bg-white/5 text-white border border-white/10"
                : "text-slate-400 hover:text-white"
            }`}
          >
            <Terminal size={12} />
            <span>Console</span>
          </button>

          {activePort && (
            <button
              onClick={() => setTab("preview")}
              className={`flex items-center space-x-2 py-1 px-3 rounded-lg text-[10px] uppercase tracking-wider font-bold transition ${
                tab === "preview"
                  ? "bg-white/5 text-white border border-white/10"
                  : "text-slate-400 hover:text-white"
              }`}
            >
              <Globe size={12} />
              <span>Live Preview</span>
            </button>
          )}
        </div>

        {tab === "console" ? (
          <button
            onClick={onClearLogs}
            title="Clear Logs"
            className="p-1.5 hover:bg-white/5 rounded-lg text-slate-400 hover:text-white transition"
          >
            <Trash2 size={13} />
          </button>
        ) : (
          <div className="text-[10px] text-slate-500 font-mono">
            http://localhost:{activePort}
          </div>
        )}
      </div>

      {/* Main Panel Content */}
      <div className="flex-1 overflow-hidden relative">
        {tab === "console" ? (
          <div className="w-full h-full overflow-y-auto p-4 space-y-1 bg-transparent">
            {logs.length === 0 ? (
              <div className="text-slate-600 italic text-center pt-8 select-none">
                No active session logs. Trigger 'Run' to execute.
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
