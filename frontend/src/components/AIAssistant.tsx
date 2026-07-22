import React, { useState, useEffect, useRef } from "react";
import { MessageSquare, Sparkles, AlertTriangle, Check, X, Copy, Lightbulb, Play, Eye } from "lucide-react";
import { Diagnostic } from "./CodeEditor";

const API_BASE = window.location.hostname === "localhost" ? "http://localhost:8080" : window.location.origin;

export interface FixProposal {
  explanation: string;
  originalCode: string;
  proposedCode: string;
}

interface Message {
  sender: "user" | "ai";
  text: string;
}

interface AIAssistantProps {
  projectName: string;
  activeFilePath: string | null;
  activeFileContent: string | null;
  diagnostics: Diagnostic[];
  onApplyFix: (proposedCode: string, originalCode: string) => void;
  isDarkTheme: boolean;
  onNavigate: (filePath: string, line: number) => void;
  selectedText: string;
  cursorLine: number;
  cursorCol: number;
}

export const AIAssistant: React.FC<AIAssistantProps> = ({
  projectName,
  activeFilePath,
  activeFileContent,
  diagnostics,
  onApplyFix,
  isDarkTheme,
  onNavigate,
  selectedText,
  cursorLine,
  cursorCol,
}) => {
  const [activeTab, setActiveTab] = useState<"insights" | "chat">("insights");
  const [insights, setInsights] = useState("");
  const [chatMessage, setChatMessage] = useState("");
  const [chatHistory, setChatHistory] = useState<Message[]>([]);
  const [loading, setLoading] = useState(false);
  const [fixLoading, setFixLoading] = useState(false);
  const [fixProposal, setFixProposal] = useState<FixProposal | null>(null);
  const [showPreview, setShowPreview] = useState(false);

  const messagesEndRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (projectName) {
      fetchInsights();
    }
  }, [projectName]);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [chatHistory, loading]);

  const fetchInsights = async () => {
    try {
      const res = await fetch(`${API_BASE}/api/projects/${projectName}/summary`);
      if (res.ok) {
        const text = await res.text();
        setInsights(text);
      }
    } catch (e) {
      setInsights("⚠️ Failed to load repository insights. Is the backend running?");
    }
  };

  const handleSendMessage = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!chatMessage.trim()) return;

    const userMsg = chatMessage;
    setChatMessage("");
    setChatHistory((prev) => [...prev, { sender: "user", text: userMsg }]);
    setLoading(true);

    try {
      const historyStr = chatHistory
        .map((m) => `${m.sender === "user" ? "User" : "AI"}: ${m.text}`)
        .join("\n");

      const response = await fetch(`${API_BASE}/api/projects/${projectName}/chat`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          message: userMsg,
          currentFilePath: activeFilePath || "",
          currentFileContent: activeFileContent || "",
          chatHistory: historyStr,
          selectedText: selectedText,
          cursorLine: cursorLine,
          cursorCol: cursorCol,
        }),
      });

      if (response.ok) {
        const aiText = await response.text();
        setChatHistory((prev) => [...prev, { sender: "ai", text: aiText }]);
      } else {
        setChatHistory((prev) => [...prev, { sender: "ai", text: "⚠️ Error contacting AI service." }]);
      }
    } catch (e) {
      setChatHistory((prev) => [...prev, { sender: "ai", text: "⚠️ Network error occurred." }]);
    } finally {
      setLoading(false);
    }
  };

  const handleExplainError = async (d: Diagnostic) => {
    setActiveTab("chat");
    setLoading(true);
    setChatHistory((prev) => [
      ...prev,
      { sender: "user", text: `Explain compilation error on line ${d.line}: "${d.message}"` },
    ]);

    try {
      const res = await fetch(
        `${API_BASE}/api/projects/${projectName}/explain-error?path=${activeFilePath}&codeLine=Line ${d.line}&error=${d.message}`,
        { method: "POST" }
      );
      if (res.ok) {
        const text = await res.text();
        setChatHistory((prev) => [...prev, { sender: "ai", text: text }]);
      } else {
        setChatHistory((prev) => [...prev, { sender: "ai", text: "⚠️ Error generating explanation." }]);
      }
    } catch (e) {
      setChatHistory((prev) => [...prev, { sender: "ai", text: "⚠️ Network error explaining error." }]);
    } finally {
      setLoading(false);
    }
  };

  const handleProposeFix = async (d: Diagnostic) => {
    setFixLoading(true);
    setFixProposal(null);
    try {
      const res = await fetch(
        `${API_BASE}/api/projects/${projectName}/fix-error?path=${activeFilePath}&line=${d.line}&error=${d.message}`,
        {
          method: "POST",
          headers: { "Content-Type": "text/plain" },
          body: activeFileContent || "",
        }
      );

      if (res.ok) {
        const json = await res.json();
        setFixProposal({
          explanation: json.explanation || "Suggested fix for error",
          originalCode: json.originalCode || "",
          proposedCode: json.proposedCode || "",
        });
      } else {
        alert("Could not generate a fix proposal.");
      }
    } catch (e) {
      alert("Network error generating fix.");
    } finally {
      setFixLoading(false);
    }
  };

  // Sync / navigation parse link utility (Interaction Principles)
  const renderMessageWithLinks = (text: string) => {
    const parts = [];
    const regex = /(\b[a-zA-Z0-9_\-\.\/]+\.java):(\d+)\b/g;
    let lastIndex = 0;
    let match;
    
    while ((match = regex.exec(text)) !== null) {
      const index = match.index;
      if (index > lastIndex) {
        parts.push(text.substring(lastIndex, index));
      }
      
      const filePath = match[1];
      const lineNum = parseInt(match[2], 10);
      
      parts.push(
        <button
          key={index}
          onClick={() => onNavigate(filePath, lineNum)}
          className="text-blue-500 dark:text-blue-400 hover:underline font-mono inline-block cursor-pointer font-bold"
        >
          {filePath.substring(filePath.lastIndexOf("/") + 1)}:{lineNum}
        </button>
      );
      
      lastIndex = regex.lastIndex;
    }
    
    if (lastIndex < text.length) {
      parts.push(text.substring(lastIndex));
    }
    
    return parts.length > 0 ? parts : text;
  };

  return (
    <div className="flex flex-col h-full bg-card border-l border-border text-foreground">
      {/* Tabs Header */}
      <div className="flex border-b border-border bg-muted/20 select-none">
        <button
          onClick={() => setActiveTab("insights")}
          className={`flex-1 flex items-center justify-center space-x-1.5 py-3 text-xs font-semibold uppercase tracking-wider border-b-2 transition-all duration-150 ${
            activeTab === "insights"
              ? "border-primary text-primary"
              : "border-transparent text-muted-foreground hover:text-foreground"
          }`}
        >
          <Sparkles size={14} />
          <span>Insights</span>
        </button>
        <button
          onClick={() => setActiveTab("chat")}
          className={`flex-1 flex items-center justify-center space-x-1.5 py-3 text-xs font-semibold uppercase tracking-wider border-b-2 transition-all duration-150 ${
            activeTab === "chat"
              ? "border-primary text-primary"
              : "border-transparent text-muted-foreground hover:text-foreground"
          }`}
        >
          <MessageSquare size={14} />
          <span>AI Assistant</span>
        </button>
      </div>

      {/* Main Tab Area */}
      <div className="flex-1 overflow-y-auto p-4 space-y-4">
        {/* Active Error Alerts */}
        {diagnostics.length > 0 && activeFilePath && (
          <div className="border border-destructive/30 bg-destructive/10 rounded-lg p-3 space-y-2">
            <div className="flex items-start space-x-2 text-destructive">
              <AlertTriangle size={16} className="mt-0.5 flex-shrink-0" />
              <div>
                <h4 className="text-xs font-bold uppercase tracking-wider">
                  Active File Diagnostic
                </h4>
                <p className="text-xs text-foreground/90 font-medium">
                  Line {diagnostics[0].line}: {diagnostics[0].message}
                </p>
                <div className="text-[9px] mt-1 text-muted-foreground font-semibold uppercase">
                  Severity: {diagnostics[0].severityLabel} | Confidence: {diagnostics[0].confidence}%
                </div>
              </div>
            </div>
            <div className="flex items-center space-x-2 pt-1">
              <button
                onClick={() => handleExplainError(diagnostics[0])}
                className="flex items-center space-x-1 bg-destructive/20 text-foreground hover:bg-destructive/30 px-2 py-1 text-[11px] font-semibold rounded transition"
              >
                <Lightbulb size={12} />
                <span>Explain Error</span>
              </button>
              <button
                onClick={() => handleProposeFix(diagnostics[0])}
                disabled={fixLoading}
                className="flex items-center space-x-1 bg-primary text-primary-foreground hover:bg-primary/95 px-2 py-1 text-[11px] font-semibold rounded disabled:opacity-50 transition"
              >
                <Sparkles size={12} />
                <span>{fixLoading ? "Generating Fix..." : "Propose Fix"}</span>
              </button>
            </div>
          </div>
        )}

        {/* Fix Proposal Viewer */}
        {fixProposal && (
          <div className="border border-primary/20 bg-primary/5 rounded-lg p-3 space-y-3">
            <div>
              <h4 className="text-xs font-bold text-primary uppercase tracking-wider mb-1">
                AI Suggested Fix
              </h4>
              <p className="text-xs text-foreground/80 leading-relaxed font-medium">
                {fixProposal.explanation}
              </p>
            </div>
            
            <div className="text-[11px] font-mono border border-border rounded overflow-hidden">
              <div className="bg-destructive/10 border-b border-border p-2">
                <span className="text-destructive font-bold uppercase mr-1">[Original]</span>
                <pre className="overflow-x-auto mt-1 max-h-32 text-foreground/90 whitespace-pre-wrap">{fixProposal.originalCode}</pre>
              </div>
              <div className="bg-emerald-500/10 p-2">
                <span className="text-emerald-500 font-bold uppercase mr-1">[Proposed]</span>
                <pre className="overflow-x-auto mt-1 max-h-32 text-foreground/90 whitespace-pre-wrap">{fixProposal.proposedCode}</pre>
              </div>
            </div>

            <div className="flex items-center justify-between">
              {/* Preview Fix (Large Diff Modal launcher) */}
              <button
                onClick={() => setShowPreview(true)}
                className="flex items-center space-x-1 hover:bg-muted-foreground/10 px-2 py-1 text-[11px] font-semibold rounded text-primary transition"
              >
                <Eye size={12} />
                <span>Preview Fix</span>
              </button>

              <div className="flex items-center space-x-2">
                <button
                  onClick={() => setFixProposal(null)}
                  className="flex items-center space-x-1 bg-muted hover:bg-muted-foreground/20 px-2 py-1 text-[11px] font-semibold rounded text-foreground transition"
                >
                  <X size={12} />
                  <span>Reject</span>
                </button>
                <button
                  onClick={() => {
                    navigator.clipboard.writeText(fixProposal.proposedCode);
                    alert("Copied to clipboard!");
                  }}
                  className="flex items-center space-x-1 bg-muted hover:bg-muted-foreground/20 px-2 py-1 text-[11px] font-semibold rounded text-foreground transition"
                >
                  <Copy size={12} />
                  <span>Copy</span>
                </button>
                <button
                  onClick={() => {
                    onApplyFix(fixProposal.proposedCode, fixProposal.originalCode);
                    setFixProposal(null);
                  }}
                  className="flex items-center space-x-1 bg-primary text-primary-foreground hover:bg-primary/95 px-2.5 py-1 text-[11px] font-semibold rounded transition"
                >
                  <Check size={12} />
                  <span>Apply</span>
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Tab Contents */}
        {activeTab === "insights" ? (
          <div className="prose prose-sm dark:prose-invert max-w-none text-sm text-foreground/90 whitespace-pre-line leading-relaxed font-sans select-text">
            {insights || (
              <div className="text-center text-xs text-muted-foreground pt-8">
                Loading repository insights...
              </div>
            )}
          </div>
        ) : (
          <div className="flex flex-col h-[400px] border border-border rounded-lg bg-background">
            <div className="flex-1 overflow-y-auto p-3 space-y-3 select-text">
              {chatHistory.length === 0 && (
                <p className="text-xs text-muted-foreground text-center mt-8 select-none">
                  Ask me anything about this repository's structure, files, or errors.
                </p>
              )}
              {chatHistory.map((msg, i) => (
                <div
                  key={i}
                  className={`flex ${msg.sender === "user" ? "justify-end" : "justify-start"}`}
                >
                  <div
                    className={`max-w-[85%] rounded-lg px-3 py-2 text-xs leading-relaxed whitespace-pre-wrap ${
                      msg.sender === "user"
                        ? "bg-primary text-primary-foreground font-medium"
                        : "bg-muted text-foreground"
                    }`}
                  >
                    {msg.sender === "ai" ? renderMessageWithLinks(msg.text) : msg.text}
                  </div>
                </div>
              ))}
              {loading && (
                <div className="flex justify-start select-none">
                  <div className="bg-muted text-muted-foreground rounded-lg px-3 py-2 text-xs italic animate-pulse">
                    Gemini is thinking...
                  </div>
                </div>
              )}
              <div ref={messagesEndRef} />
            </div>

            <form onSubmit={handleSendMessage} className="p-2 border-t border-border flex items-center space-x-1 select-none">
              <input
                type="text"
                value={chatMessage}
                onChange={(e) => setChatMessage(e.target.value)}
                placeholder="Ask AI..."
                className="flex-1 bg-muted/60 text-xs rounded pl-2.5 pr-2 py-2 border border-border focus:outline-none focus:ring-1 focus:ring-primary focus:border-primary"
              />
              <button
                type="submit"
                disabled={loading}
                className="bg-primary text-primary-foreground hover:bg-primary/95 p-2 rounded disabled:opacity-50 transition"
              >
                <MessageSquare size={13} />
              </button>
            </form>
          </div>
        )}
      </div>

      {/* Large Diff Preview Modal (Feature 11 / Preview Fix) */}
      {showPreview && fixProposal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-6 select-none">
          <div className="bg-card border border-border rounded-xl w-full max-w-4xl shadow-2xl flex flex-col h-[85vh]">
            <div className="flex items-center justify-between p-4 border-b border-border">
              <h3 className="font-bold text-base text-primary uppercase tracking-wide flex items-center space-x-2">
                <Sparkles size={18} />
                <span>Proposed Change Preview</span>
              </h3>
              <button
                onClick={() => setShowPreview(false)}
                className="text-muted-foreground hover:text-foreground text-sm font-semibold p-1 hover:bg-muted rounded"
              >
                Close
              </button>
            </div>
            
            <div className="p-4 border-b border-border bg-muted/10 text-xs text-foreground/80 leading-relaxed select-text">
              <span className="font-bold text-primary">Explanation:</span> {fixProposal.explanation}
            </div>

            {/* Split Diff display */}
            <div className="flex-1 overflow-hidden grid grid-cols-2 gap-4 p-4 min-h-0 select-text">
              <div className="flex flex-col h-full border border-border rounded overflow-hidden">
                <div className="bg-destructive/10 border-b border-border p-2 text-xs font-bold text-destructive uppercase select-none">
                  Original Code
                </div>
                <pre className="flex-1 overflow-auto p-3 font-mono text-xs bg-muted/5 leading-relaxed whitespace-pre">{fixProposal.originalCode}</pre>
              </div>
              <div className="flex flex-col h-full border border-border rounded overflow-hidden">
                <div className="bg-emerald-500/10 border-b border-border p-2 text-xs font-bold text-emerald-500 uppercase select-none">
                  Proposed Fix
                </div>
                <pre className="flex-1 overflow-auto p-3 font-mono text-xs bg-muted/5 leading-relaxed whitespace-pre">{fixProposal.proposedCode}</pre>
              </div>
            </div>

            {/* Footer actions */}
            <div className="p-4 border-t border-border flex justify-end space-x-2 select-none">
              <button
                onClick={() => setShowPreview(false)}
                className="bg-muted hover:bg-muted-foreground/20 text-xs font-semibold px-4 py-2 rounded transition"
              >
                Close Preview
              </button>
              <button
                onClick={() => {
                  onApplyFix(fixProposal.proposedCode, fixProposal.originalCode);
                  setFixProposal(null);
                  setShowPreview(false);
                }}
                className="bg-primary text-primary-foreground hover:bg-primary/95 text-xs font-semibold px-5 py-2 rounded transition shadow-sm"
              >
                Apply Fix to File
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
