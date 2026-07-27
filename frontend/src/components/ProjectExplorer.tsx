import React, { useState, useEffect } from "react";
import { Folder, FolderOpen, File as FileIcon, Search, Plus, Trash2, Edit2, RotateCw, ChevronRight, ChevronDown, Move, Info } from "lucide-react";

export interface FileNode {
  name: string;
  path: string;
  isDirectory: boolean;
  size: number;
  children?: FileNode[] | null;
}

interface FileMetadata {
  name: string;
  type: string;
  size: number;
  lineCount: number;
  lastModified: number;
  dependencies: string[];
  references: string[];
}

interface ProjectExplorerProps {
  projectName: string;
  fileTree: FileNode | null;
  onFileSelect: (path: string) => void;
  selectedPath: string | null;
  onRefresh: () => void;
  onCreateNode: (path: string, isFolder: boolean) => void;
  onDeleteNode: (path: string) => void;
  onRenameNode: (path: string, newName: string) => void;
  onMoveNode: (sourcePath: string, targetDirPath: string) => void;
}

export const ProjectExplorer: React.FC<ProjectExplorerProps> = ({
  projectName,
  fileTree,
  onFileSelect,
  selectedPath,
  onRefresh,
  onCreateNode,
  onDeleteNode,
  onRenameNode,
  onMoveNode,
}) => {
  const [searchQuery, setSearchQuery] = useState("");
  const [expandedFolders, setExpandedFolders] = useState<Record<string, boolean>>({ "": true });
  const [metadata, setMetadata] = useState<FileMetadata | null>(null);
  const [showMetadata, setShowMetadata] = useState(false);

  // Fetch metadata when selection changes
  useEffect(() => {
    if (selectedPath && !selectedPath.endsWith("/") && selectedPath.includes(".") && projectName) {
      const apiBase = window.location.hostname === "localhost" ? "http://localhost:8080" : window.location.origin;
      fetch(`${apiBase}/api/projects/${projectName}/metadata?path=${encodeURIComponent(selectedPath)}`)
        .then((res) => {
          if (res.ok) return res.json();
          throw new Error();
        })
        .then((data) => {
          setMetadata(data);
          setShowMetadata(true);
        })
        .catch(() => {
          setMetadata(null);
          setShowMetadata(false);
        });
    } else {
      setMetadata(null);
      setShowMetadata(false);
    }
  }, [selectedPath, projectName]);

  const handleCreate = (isFolder: boolean) => {
    const parentPath = selectedPath && !selectedPath.includes(".") ? selectedPath : "";
    const name = prompt(`Enter ${isFolder ? "folder" : "file"} name:`);
    if (name) {
      const fullPath = parentPath ? `${parentPath}/${name}` : name;
      onCreateNode(fullPath, isFolder);
    }
  };

  const handleDelete = () => {
    if (selectedPath && confirm(`Are you sure you want to delete ${selectedPath}?`)) {
      onDeleteNode(selectedPath);
    }
  };

  const handleRename = () => {
    if (!selectedPath) return;
    const oldName = selectedPath.substring(selectedPath.lastIndexOf("/") + 1);
    const newName = prompt("Enter new name:", oldName);
    if (newName && newName !== oldName) {
      onRenameNode(selectedPath, newName);
    }
  };

  const handleMove = () => {
    if (!selectedPath) return;
    const targetDir = prompt("Enter target directory path relative to project root (e.g. src/main/java):");
    if (targetDir !== null) {
      onMoveNode(selectedPath, targetDir);
    }
  };

  const getAllFolderPaths = (node: FileNode, paths: string[] = []) => {
    if (node.isDirectory && node.path) {
      paths.push(node.path);
    }
    if (node.children) {
      node.children.forEach((c) => getAllFolderPaths(c, paths));
    }
    return paths;
  };

  const handleExpandAll = () => {
    if (!fileTree) return;
    const paths = getAllFolderPaths(fileTree);
    const expanded: Record<string, boolean> = { "": true };
    paths.forEach((p) => {
      expanded[p] = true;
    });
    setExpandedFolders(expanded);
  };

  const handleCollapseAll = () => {
    setExpandedFolders({ "": true });
  };

  const matchesSearch = (node: FileNode, query: string): boolean => {
    if (!query) return true;
    if (node.name.toLowerCase().includes(query.toLowerCase())) return true;
    if (node.children) {
      return node.children.some((child) => matchesSearch(child, query));
    }
    return false;
  };

  const renderTree = (node: FileNode) => {
    if (!matchesSearch(node, searchQuery)) return null;

    const isFolder = node.isDirectory;
    const path = node.path;
    const isExpanded = expandedFolders[path] || false;
    const isSelected = selectedPath === path;

    return (
      <div key={path} className="select-none my-0.5">
        <div
          className={`flex items-center justify-between py-1 px-2 cursor-pointer text-xs rounded-lg transition-all duration-150 ${
            isSelected
              ? "bg-gradient-to-r from-indigo-500/20 to-blue-500/20 text-indigo-300 font-semibold border border-indigo-500/35 shadow-[0_0_12px_rgba(99,102,241,0.15)]"
              : "hover:bg-white/5 text-slate-400 hover:text-white border border-transparent"
          }`}
          onClick={() => {
            if (isFolder) {
              setExpandedFolders((prev) => ({ ...prev, [path]: !prev[path] }));
            } else {
              onFileSelect(path);
            }
          }}
        >
          <div className="flex items-center space-x-2 min-w-0">
            {isFolder ? (
              <span className="flex-shrink-0 text-slate-500">
                {isExpanded ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
              </span>
            ) : (
              <span className="w-3" />
            )}
            
            <span className="flex-shrink-0">
              {isFolder ? (
                isExpanded ? (
                  <FolderOpen size={15} className={isSelected ? "text-indigo-400" : "text-amber-500/90"} />
                ) : (
                  <Folder size={15} className={isSelected ? "text-indigo-400" : "text-amber-500/90"} />
                )
              ) : (
                <FileIcon size={15} className={isSelected ? "text-blue-400" : "text-slate-400"} />
              )}
            </span>
            <span className="truncate tracking-wide">{node.name}</span>
          </div>
        </div>

        {isFolder && isExpanded && node.children && (
          <div className="pl-3.5 border-l border-white/5 ml-3.5 mt-0.5">
            {node.children.map((child) => renderTree(child))}
          </div>
        )}
      </div>
    );
  };

  const formatBytes = (bytes: number) => {
    if (bytes === 0) return "0 Bytes";
    const k = 1024;
    const sizes = ["Bytes", "KB", "MB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + " " + sizes[i];
  };

  return (
    <div className="flex flex-col h-full bg-zinc-950 border-r border-white/5 text-slate-300 select-none">
      {/* Explorer Header */}
      <div className="flex items-center justify-between p-3.5 border-b border-white/5 bg-zinc-900/10">
        <h2 className="font-semibold text-xs tracking-wider uppercase text-slate-400">
          Explorer
        </h2>
        <div className="flex items-center space-x-1 bg-white/5 p-1 rounded-lg border border-white/5">
          <button
            onClick={() => handleCreate(false)}
            title="New File"
            className="p-1 hover:bg-white/10 hover:text-white rounded text-slate-400 transition"
          >
            <Plus size={14} />
          </button>
          <button
            onClick={() => handleCreate(true)}
            title="New Folder"
            className="p-1 hover:bg-white/10 hover:text-white rounded text-slate-400 transition"
          >
            <Folder size={14} />
          </button>
          <button
            onClick={handleRename}
            disabled={!selectedPath}
            title="Rename"
            className={`p-1 hover:bg-white/10 rounded transition ${
              selectedPath ? "text-slate-300 hover:text-white" : "text-slate-600 opacity-40"
            }`}
          >
            <Edit2 size={14} />
          </button>
          <button
            onClick={handleMove}
            disabled={!selectedPath}
            title="Move File/Folder"
            className={`p-1 hover:bg-white/10 rounded transition ${
              selectedPath ? "text-slate-300 hover:text-white" : "text-slate-600 opacity-40"
            }`}
          >
            <Move size={14} />
          </button>
          <button
            onClick={handleDelete}
            disabled={!selectedPath}
            title="Delete"
            className={`p-1 hover:bg-rose-500/20 rounded transition ${
              selectedPath ? "text-rose-400 hover:text-rose-300" : "text-slate-600 opacity-40"
            }`}
          >
            <Trash2 size={14} />
          </button>
          <button
            onClick={onRefresh}
            title="Refresh"
            className="p-1 hover:bg-white/10 hover:text-white rounded text-slate-400 transition"
          >
            <RotateCw size={14} />
          </button>
        </div>
      </div>

      {/* Expand/Collapse Header Bar */}
      <div className="flex items-center justify-between px-4 py-1.5 border-b border-white/5 bg-zinc-900/25 text-[10px] text-slate-400">
        <span className="font-medium">Tree depth</span>
        <div className="flex space-x-2 font-semibold">
          <button onClick={handleExpandAll} className="hover:text-indigo-400 transition">Expand All</button>
          <span className="text-white/10">|</span>
          <button onClick={handleCollapseAll} className="hover:text-indigo-400 transition">Collapse All</button>
        </div>
      </div>

      {/* Search Input */}
      <div className="p-3 border-b border-white/5 bg-zinc-900/5">
        <div className="relative">
          <Search size={13} className="absolute left-3 top-2.5 text-slate-500" />
          <input
            type="text"
            placeholder="Search files..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full bg-slate-900/50 text-xs rounded-lg pl-8 pr-3 py-2 border border-white/5 focus:outline-none focus:ring-1 focus:ring-indigo-500 focus:border-indigo-500 text-slate-200 placeholder-slate-600 transition"
          />
        </div>
      </div>

      {/* Tree View */}
      <div className="flex-1 overflow-y-auto p-3">
        {fileTree ? (
          <div>
            <div className="flex items-center space-x-1.5 px-2 py-1 mb-2 text-[10px] font-bold text-slate-500 uppercase tracking-wider">
              <span>Workspace</span>
            </div>
            {fileTree.children?.map((child) => renderTree(child))}
          </div>
        ) : (
          <div className="text-center text-xs text-slate-600 mt-12 italic">
            No project workspaces found
          </div>
        )}
      </div>

      {/* Collapsible File Information Panel (Feature 12) */}
      {showMetadata && metadata && (
        <div className="border-t border-white/5 bg-zinc-950/90 p-4 space-y-3.5 select-text">
          <div className="flex items-center justify-between">
            <h4 className="text-[10px] font-bold uppercase tracking-wider text-slate-400 flex items-center space-x-1.5">
              <Info size={11} className="text-indigo-400" />
              <span>Metadata Details</span>
            </h4>
            <button
              onClick={() => setShowMetadata(false)}
              className="text-slate-500 hover:text-slate-300 text-[10px] uppercase font-semibold"
            >
              Hide
            </button>
          </div>
          <div className="text-[11px] space-y-1.5 text-slate-300">
            <div className="flex justify-between border-b border-white/5 pb-1"><span className="text-slate-500">Name:</span> <span className="font-semibold truncate max-w-[130px] text-slate-200">{metadata.name}</span></div>
            <div className="flex justify-between border-b border-white/5 pb-1"><span className="text-slate-500">Type:</span> <span className="uppercase text-slate-200">{metadata.type}</span></div>
            <div className="flex justify-between border-b border-white/5 pb-1"><span className="text-slate-500">Size:</span> <span className="text-slate-200">{formatBytes(metadata.size)}</span></div>
            <div className="flex justify-between border-b border-white/5 pb-1"><span className="text-slate-500">Lines:</span> <span className="text-slate-200">{metadata.lineCount}</span></div>
            <div className="flex justify-between pb-0.5"><span className="text-slate-500">Modified:</span> <span className="text-slate-200">{new Date(metadata.lastModified).toLocaleDateString()}</span></div>
          </div>

          {/* Dependencies list */}
          {metadata.dependencies.length > 0 && (
            <div className="space-y-1.5 pt-1">
              <span className="text-[9px] font-bold text-slate-400 uppercase tracking-wider">Imports ({metadata.dependencies.length})</span>
              <div className="max-h-20 overflow-y-auto border border-white/5 rounded-lg p-2 bg-slate-900/30 text-[10px] space-y-1">
                {metadata.dependencies.map((dep, i) => (
                  <div key={i} className="truncate text-slate-400 font-mono" title={dep}>{dep}</div>
                ))}
              </div>
            </div>
          )}

          {/* References list with click support */}
          {metadata.references.length > 0 && (
            <div className="space-y-1.5 pt-1">
              <span className="text-[9px] font-bold text-slate-400 uppercase tracking-wider">References ({metadata.references.length})</span>
              <div className="max-h-20 overflow-y-auto border border-white/5 rounded-lg p-2 bg-slate-900/30 text-[10px] space-y-1">
                {metadata.references.map((ref, i) => {
                  const refName = ref.substring(ref.lastIndexOf("/") + 1);
                  return (
                    <button
                      key={i}
                      onClick={() => onFileSelect(ref)}
                      className="w-full text-left truncate text-indigo-400 hover:text-indigo-300 hover:underline font-mono"
                      title={`Open ${ref}`}
                    >
                      {refName}
                    </button>
                  );
                })}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
};
