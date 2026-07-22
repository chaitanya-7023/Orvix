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
      <div key={path} className="select-none">
        <div
          className={`flex items-center justify-between py-1 px-2 cursor-pointer text-sm rounded transition-all duration-150 ${
            isSelected
              ? "bg-primary text-primary-foreground font-medium"
              : "hover:bg-muted text-foreground/80 hover:text-foreground"
          }`}
          onClick={() => {
            if (isFolder) {
              setExpandedFolders((prev) => ({ ...prev, [path]: !prev[path] }));
            } else {
              onFileSelect(path);
            }
          }}
        >
          <div className="flex items-center space-x-1.5 min-w-0">
            {isFolder ? (
              <span className="flex-shrink-0">
                {isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
              </span>
            ) : (
              <span className="w-3.5" />
            )}
            
            <span className="flex-shrink-0">
              {isFolder ? (
                isExpanded ? (
                  <FolderOpen size={16} className={isSelected ? "text-primary-foreground" : "text-amber-500"} />
                ) : (
                  <Folder size={16} className={isSelected ? "text-primary-foreground" : "text-amber-500"} />
                )
              ) : (
                <FileIcon size={16} className={isSelected ? "text-primary-foreground" : "text-blue-400"} />
              )}
            </span>
            <span className="truncate">{node.name}</span>
          </div>
        </div>

        {isFolder && isExpanded && node.children && (
          <div className="pl-4 border-l border-muted-foreground/15 ml-3 mt-0.5">
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
    <div className="flex flex-col h-full bg-card border-r border-border text-foreground">
      {/* Explorer Header */}
      <div className="flex items-center justify-between p-3 border-b border-border">
        <h2 className="font-semibold text-sm tracking-wide uppercase text-muted-foreground">
          Project Explorer
        </h2>
        <div className="flex items-center space-x-1">
          <button
            onClick={() => handleCreate(false)}
            title="New File"
            className="p-1 hover:bg-muted rounded text-foreground/75 hover:text-foreground"
          >
            <Plus size={15} />
          </button>
          <button
            onClick={() => handleCreate(true)}
            title="New Folder"
            className="p-1 hover:bg-muted rounded text-foreground/75 hover:text-foreground"
          >
            <Folder size={15} />
          </button>
          <button
            onClick={handleRename}
            disabled={!selectedPath}
            title="Rename"
            className={`p-1 hover:bg-muted rounded ${
              selectedPath ? "text-foreground/75 hover:text-foreground" : "text-muted-foreground/45"
            }`}
          >
            <Edit2 size={15} />
          </button>
          <button
            onClick={handleMove}
            disabled={!selectedPath}
            title="Move File/Folder"
            className={`p-1 hover:bg-muted rounded ${
              selectedPath ? "text-foreground/75 hover:text-foreground" : "text-muted-foreground/45"
            }`}
          >
            <Move size={15} />
          </button>
          <button
            onClick={handleDelete}
            disabled={!selectedPath}
            title="Delete"
            className={`p-1 hover:bg-muted rounded ${
              selectedPath ? "text-destructive hover:text-destructive" : "text-muted-foreground/45"
            }`}
          >
            <Trash2 size={15} />
          </button>
          <button
            onClick={onRefresh}
            title="Refresh"
            className="p-1 hover:bg-muted rounded text-foreground/75 hover:text-foreground"
          >
            <RotateCw size={15} />
          </button>
        </div>
      </div>

      {/* Expand/Collapse Header Bar */}
      <div className="flex items-center justify-between px-3 py-1 border-b border-border bg-muted/10 text-[10px]">
        <span className="text-muted-foreground">Expansion tools</span>
        <div className="flex space-x-2 font-medium">
          <button onClick={handleExpandAll} className="hover:text-primary transition">Expand All</button>
          <span className="text-border">|</span>
          <button onClick={handleCollapseAll} className="hover:text-primary transition">Collapse All</button>
        </div>
      </div>

      {/* Search Input */}
      <div className="p-2.5 border-b border-border bg-muted/20">
        <div className="relative">
          <Search size={14} className="absolute left-2.5 top-2.5 text-muted-foreground" />
          <input
            type="text"
            placeholder="Search files..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full bg-muted/65 text-xs rounded-md pl-8 pr-3 py-2 border border-border focus:outline-none focus:ring-1 focus:ring-primary focus:border-primary"
          />
        </div>
      </div>

      {/* Tree View */}
      <div className="flex-1 overflow-y-auto p-2">
        {fileTree ? (
          <div>
            <div className="flex items-center space-x-1.5 px-2 py-1 mb-1 text-xs font-semibold text-muted-foreground uppercase">
              <span>{projectName}</span>
            </div>
            {fileTree.children?.map((child) => renderTree(child))}
          </div>
        ) : (
          <div className="text-center text-xs text-muted-foreground mt-8">
            No project imported
          </div>
        )}
      </div>

      {/* Collapsible File Information Panel (Feature 12) */}
      {showMetadata && metadata && (
        <div className="border-t border-border bg-muted/15 p-3 space-y-2 select-text">
          <div className="flex items-center justify-between">
            <h4 className="text-[11px] font-bold uppercase tracking-wider text-muted-foreground flex items-center space-x-1.5">
              <Info size={12} className="text-primary" />
              <span>File Details</span>
            </h4>
            <button
              onClick={() => setShowMetadata(false)}
              className="text-muted-foreground hover:text-foreground text-[10px]"
            >
              Hide
            </button>
          </div>
          <div className="text-[11px] space-y-1">
            <div className="flex justify-between"><span className="text-muted-foreground">Name:</span> <span className="font-semibold truncate max-w-[150px]">{metadata.name}</span></div>
            <div className="flex justify-between"><span className="text-muted-foreground">Type:</span> <span className="uppercase">{metadata.type}</span></div>
            <div className="flex justify-between"><span className="text-muted-foreground">Size:</span> <span>{formatBytes(metadata.size)}</span></div>
            <div className="flex justify-between"><span className="text-muted-foreground">Lines:</span> <span>{metadata.lineCount}</span></div>
            <div className="flex justify-between"><span className="text-muted-foreground">Modified:</span> <span>{new Date(metadata.lastModified).toLocaleDateString()}</span></div>
          </div>

          {/* Dependencies list */}
          {metadata.dependencies.length > 0 && (
            <div className="space-y-1 pt-1.5">
              <span className="text-[10px] font-bold text-muted-foreground uppercase">Imports ({metadata.dependencies.length})</span>
              <div className="max-h-16 overflow-y-auto border border-border rounded p-1 bg-background text-[10px] space-y-0.5">
                {metadata.dependencies.map((dep, i) => (
                  <div key={i} className="truncate text-foreground/80 font-mono" title={dep}>{dep}</div>
                ))}
              </div>
            </div>
          )}

          {/* References list with click support */}
          {metadata.references.length > 0 && (
            <div className="space-y-1 pt-1.5">
              <span className="text-[10px] font-bold text-muted-foreground uppercase">References ({metadata.references.length})</span>
              <div className="max-h-20 overflow-y-auto border border-border rounded p-1 bg-background text-[10px] space-y-0.5">
                {metadata.references.map((ref, i) => {
                  const refName = ref.substring(ref.lastIndexOf("/") + 1);
                  return (
                    <button
                      key={i}
                      onClick={() => onFileSelect(ref)}
                      className="w-full text-left truncate text-blue-500 dark:text-blue-400 hover:underline font-mono"
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
