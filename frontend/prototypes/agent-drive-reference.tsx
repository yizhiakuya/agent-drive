import React, { useState, useEffect, useRef } from 'react';

const Icons = {
  Drive: () => <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"/></svg>,
  Folder: () => <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"/></svg>,
  File: () => <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/></svg>,
  Image: () => <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><rect width="18" height="18" x="3" y="3" rx="2" ry="2"/><circle cx="9" cy="9" r="2"/><path d="m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21"/></svg>,
  ArrowRight: () => <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M5 12h14"/><path d="m12 5 7 7-7 7"/></svg>,
  CheckCircle: () => <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><path d="m9 11 3 3L22 4"/></svg>,
  AlertCircle: () => <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" x2="12" y1="8" y2="12"/><line x1="12" x2="12.01" y1="16" y2="16"/></svg>,
  ShieldAlert: () => <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10"/><path d="M12 8v4"/><path d="M12 16h.01"/></svg>,
  Tasks: () => <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><path d="m9 11 3 3L22 4"/></svg>,
  ChevronDown: () => <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="m6 9 6 6 6-6"/></svg>,
  ChevronRight: () => <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="m9 18 6-6-6-6"/></svg>,
  Brain: () => <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M12 5a3 3 0 1 0-5.997.125 4 4 0 0 0-2.526 5.77 4 4 0 0 0 .556 6.588A4 4 0 1 0 12 18Z"/><path d="M12 5a3 3 0 1 1 5.997.125 4 4 0 0 1 2.526 5.77 4 4 0 0 1-.556 6.588A4 4 0 1 1 12 18Z"/><path d="M15 13a4.5 4.5 0 0 1-3-4 4.5 4.5 0 0 1-3 4 4.5 4.5 0 0 1-3-4"/></svg>,
  Code: () => <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>,
  Loader: () => <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="animate-spin"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>,
  Menu: () => <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><line x1="4" x2="20" y1="12" y2="12"/><line x1="4" x2="20" y1="6" y2="6"/><line x1="4" x2="20" y1="18" y2="18"/></svg>,
  Trash: () => <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M3 6h18"/><path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"/><path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"/></svg>,
  Cpu: () => <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><rect x="4" y="4" width="16" height="16" rx="2"/><rect x="9" y="9" width="6" height="6"/><line x1="9" y1="1" x2="9" y2="4"/><line x1="15" y1="1" x2="15" y2="4"/><line x1="9" y1="20" x2="9" y2="23"/><line x1="15" y1="20" x2="15" y2="23"/><line x1="20" y1="9" x2="23" y2="9"/><line x1="20" y1="15" x2="23" y2="15"/><line x1="1" y1="9" x2="4" y2="9"/><line x1="1" y1="15" x2="4" y2="15"/></svg>,
  Settings: () => <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/></svg>
};

const MOCK_FILES = [
  { id: 'f1', name: '2026年度业务规划.docx', type: 'file', size: '1.8 MB', updated: '今天 10:24', status: 'indexed', path: '/私人空间/规划/' },
  { id: 'd1', name: 'Q3_财务预算草案.pdf', type: 'file', size: '3.4 MB', updated: '昨天 14:12', status: 'indexed', path: '/私人空间/财务/' },
  { id: 'i1', name: '差旅报销发票_8842.jpg', type: 'image', size: '4.2 MB', updated: '3天前', status: 'failed', error: 'OCR 识别超时 (Provider timeout)', path: '/私人空间/财务/发票/' },
  { id: 'f2', name: '产品架构设计说明.md', type: 'file', size: '512 KB', updated: '4天前', status: 'indexed', path: '/私人空间/研发/' },
];

const StatusBadge = ({ status, error }) => {
  if (status === 'indexed') return (
    <div className="flex items-center gap-1 text-[10px] font-mono font-medium text-emerald-700 bg-emerald-50 px-2 py-0.5 rounded border border-emerald-200">
      <Icons.CheckCircle /> 已索引
    </div>
  );
  if (status === 'failed') return (
    <div className="flex items-center gap-1 text-[10px] font-mono font-medium text-rose-700 bg-rose-50 px-2 py-0.5 rounded border border-rose-200" title={error}>
      <Icons.AlertCircle /> 失败: {error || '未知错误'}
    </div>
  );
  return null;
};

// 思考过程折叠组件
const ThoughtProcessBlock = ({ thoughts, isThinking }) => {
  const [expanded, setExpanded] = useState(false);
  if (!thoughts && !isThinking) return null;

  return (
    <div className="my-2 max-w-2xl">
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex items-center gap-2 px-2.5 py-1.5 rounded-md hover:bg-zinc-100 text-xs font-mono text-zinc-500 transition-colors border border-transparent hover:border-zinc-200"
      >
        {isThinking ? <Icons.Loader /> : <Icons.Brain />}
        <span>{isThinking ? 'Agent 深度思考中...' : `已完成内部推理过程 (${thoughts?.length || 0} 步)`}</span>
        <div className={`transform transition-transform ${expanded ? 'rotate-180' : ''}`}>
          <Icons.ChevronDown />
        </div>
      </button>

      {expanded && (
        <div className="mt-2 p-3 bg-zinc-50 border border-zinc-200 rounded-lg font-mono text-xs text-zinc-600 space-y-1.5 shadow-sm">
          {thoughts ? (
            thoughts.map((t, i) => (
              <div key={i} className="flex gap-2">
                <span className="text-zinc-400 select-none">#</span>
                <span>{t}</span>
              </div>
            ))
          ) : (
            <div className="animate-pulse flex gap-2">
              <span className="text-zinc-400">#</span>
              <span>Analyzing context vector boundaries...</span>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

// 工具调用展示（OpenClaw 风格）
const ToolCallBadge = ({ toolName, args, status, result }) => {
  const [expanded, setExpanded] = useState(false);

  return (
    <div className="my-2 max-w-xl border border-zinc-200 rounded-lg bg-white overflow-hidden shadow-sm">
      <div
        className="flex items-center justify-between px-3.5 py-2.5 cursor-pointer hover:bg-zinc-50 bg-zinc-50/60 border-b border-zinc-100"
        onClick={() => setExpanded(!expanded)}
      >
        <div className="flex items-center gap-2.5">
          <div className="text-zinc-400"><Icons.Code /></div>
          <span className="text-xs font-mono font-semibold text-zinc-800">tool_call: {toolName}()</span>
        </div>
        <div className="flex items-center gap-2">
          {status === 'pending' ? (
             <span className="flex h-2 w-2 relative">
               <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-amber-400 opacity-75"></span>
               <span className="relative inline-flex rounded-full h-2 w-2 bg-amber-500"></span>
             </span>
          ) : status === 'success' ? (
             <span className="h-2 w-2 rounded-full bg-emerald-500"></span>
          ) : (
             <span className="h-2 w-2 rounded-full bg-rose-500"></span>
          )}
          <div className={`transform transition-transform text-zinc-400 ${expanded ? 'rotate-90' : ''}`}>
             <Icons.ChevronRight />
          </div>
        </div>
      </div>

      {expanded && (
        <div className="p-3 bg-zinc-900 text-zinc-200 font-mono text-[11px] overflow-x-auto space-y-3">
           <div>
             <div className="text-zinc-500 mb-1 select-none">// Arguments:</div>
             <pre className="m-0 text-amber-300">{JSON.stringify(args, null, 2)}</pre>
           </div>
           {result && (
             <div className="border-t border-zinc-800 pt-2">
               <div className="text-zinc-500 mb-1 select-none">// Output Result:</div>
               <pre className="m-0 text-emerald-300">{result}</pre>
             </div>
           )}
        </div>
      )}
    </div>
  );
};

export default function App() {
  const [input, setInput] = useState('');
  const [isProcessing, setIsProcessing] = useState(false);
  const [thinkLevel, setThinkLevel] = useState('Deep'); // Normal, Deep, Exhaustive

  const [isSidebarOpen, setIsSidebarOpen] = useState(false);
  const [isTasksOpen, setIsTasksOpen] = useState(false);

  const streamEndRef = useRef(null);

  const [stream, setStream] = useState([
    {
      id: 'sys-1', role: 'system', type: 'text',
      content: 'Agent Drive 私有向量空间已就绪。所有后台任务正常运行中。请下达指令。'
    }
  ]);

  const scrollToBottom = () => {
    streamEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };
  useEffect(scrollToBottom, [stream, isProcessing]);

  const handleCommand = (e) => {
    if (e) e.preventDefault();
    if (!input.trim() || isProcessing) return;

    const cmd = input.trim();
    setInput('');
    setStream(prev => [...prev, { id: Date.now(), role: 'user', content: cmd }]);
    setIsProcessing(true);

    setTimeout(() => {
      const thoughtId = Date.now() + 1;
      let thoughts = [
        `Parsing intent with thinking level [${thinkLevel}]...`,
        "Scanning local vector indexes for semantic matches...",
        "Executing file metadata filtering and privilege check..."
      ];

      setStream(prev => [...prev, {
        id: thoughtId,
        role: 'system',
        type: 'thoughts',
        thoughts: thoughts
      }]);

      setTimeout(() => {
        if (cmd.includes('发票') || cmd.includes('报销')) {
           setStream(prev => [
             ...prev,
             {
               id: Date.now() + 2,
               role: 'system',
               type: 'tool-call',
               toolName: 'fs_vector_search',
               args: { query: "发票 OR 报销", path: "/私人空间/财务", threshold: 0.85 },
               status: 'success',
               result: "Matched 1 file: [差旅报销发票_8842.jpg]"
             },
             {
               id: Date.now() + 3,
               role: 'system',
               type: 'text',
               content: '我为您找到了未归档的报销发票。准备将其移动至 `/私人空间/财务/已归档/` 目录。高风险操作需要您的授权确认：'
             },
             {
               id: Date.now() + 4,
               role: 'system',
               type: 'action-auth',
               action: 'MOVE_FILES_BATCH',
               targets: ['差旅报销发票_8842.jpg'],
               destination: '/私人空间/财务/已归档/'
             }
           ]);
        } else {
           setStream(prev => [
             ...prev,
             { id: Date.now() + 2, role: 'system', type: 'text', content: `已收到您的指令：「${cmd}」。全盘向量索引已检索完毕，未发现高风险冲突。` }
           ]);
        }
        setIsProcessing(false);
      }, 1200);

    }, 800);
  };

  return (
    <div className="h-screen w-full bg-white flex flex-col text-zinc-900 font-sans relative overflow-hidden">

      {/* 顶部规范化导航栏 (Header) - 解决重叠问题 */}
      <header className="h-14 shrink-0 flex items-center justify-between px-6 border-b border-zinc-200 bg-white/90 backdrop-blur-md z-30">
        <div className="flex items-center gap-3">
          <button
            onClick={() => setIsSidebarOpen(!isSidebarOpen)}
            className="p-2 rounded-lg hover:bg-zinc-100 text-zinc-600 transition-colors"
            title="切换侧边栏"
          >
            <Icons.Menu />
          </button>
          <div className="font-semibold text-sm tracking-tight text-zinc-900 flex items-center gap-2">
            <Icons.Drive /> Agent Drive
            <span className="text-[10px] font-mono font-normal text-zinc-500 bg-zinc-100 px-1.5 py-0.5 rounded">私有部署</span>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <button
            onClick={() => setIsTasksOpen(true)}
            className="flex items-center gap-2 px-3 py-1.5 rounded-lg border border-zinc-200 bg-white hover:bg-zinc-50 text-xs font-medium text-zinc-700 shadow-sm transition-colors"
          >
            <span className="h-2 w-2 rounded-full bg-amber-500 animate-pulse"></span>
            <Icons.Tasks /> 任务列表
          </button>
        </div>
      </header>

      {/* 左侧可折叠侧边栏 Drawer */}
      {isSidebarOpen && (
        <div className="fixed inset-0 z-50 flex">
          <div className="w-72 bg-white border-r border-zinc-200 shadow-2xl flex flex-col h-full animate-in slide-in-from-left duration-200">
            <div className="p-4 border-b border-zinc-200 flex items-center justify-between bg-zinc-50">
              <div className="font-semibold text-xs tracking-wider uppercase text-zinc-500 flex items-center gap-2">
                <Icons.Drive /> 资产导航
              </div>
              <button onClick={() => setIsSidebarOpen(false)} className="text-zinc-400 hover:text-zinc-900 p-1">
                ✕
              </button>
            </div>
            <div className="flex-1 p-3 space-y-1 overflow-y-auto">
              <button className="w-full text-left px-3 py-2 text-sm font-medium rounded-lg text-zinc-800 bg-zinc-100 flex items-center gap-2">
                <Icons.Folder /> 智能工作流画布
              </button>
              <button className="w-full text-left px-3 py-2 text-sm font-medium rounded-lg text-zinc-600 hover:bg-zinc-50 flex items-center gap-2">
                <Icons.File /> 全部文件 (42)
              </button>
              <button className="w-full text-left px-3 py-2 text-sm font-medium rounded-lg text-zinc-600 hover:bg-zinc-50 flex items-center gap-2">
                <Icons.Trash /> 回收站
              </button>
              <div className="pt-4 pb-2 text-[10px] font-semibold text-zinc-400 uppercase tracking-wider px-3">系统与配置</div>
              <button className="w-full text-left px-3 py-2 text-sm font-medium rounded-lg text-zinc-600 hover:bg-zinc-50 flex items-center gap-2">
                <Icons.Cpu /> 模型服务配置
              </button>
              <button className="w-full text-left px-3 py-2 text-sm font-medium rounded-lg text-zinc-600 hover:bg-zinc-50 flex items-center gap-2">
                <Icons.Settings /> 安全与凭据
              </button>
            </div>
            <div className="p-4 border-t border-zinc-200 bg-zinc-50 text-xs text-zinc-500">
              <div className="flex justify-between mb-1">
                <span>本地物理存储</span>
                <span className="font-mono">42.8 GB / 500 GB</span>
              </div>
              <div className="w-full bg-zinc-200 h-1.5 rounded-full overflow-hidden">
                <div className="bg-zinc-800 h-full w-[18%]"></div>
              </div>
            </div>
          </div>
          <div className="flex-1 bg-black/20 backdrop-blur-xs" onClick={() => setIsSidebarOpen(false)}></div>
        </div>
      )}

      {/* 右侧任务列表 Drawer */}
      {isTasksOpen && (
        <div className="fixed inset-0 z-50 flex justify-end">
          <div className="w-80 bg-white border-l border-zinc-200 shadow-2xl flex flex-col h-full animate-in slide-in-from-right duration-200">
            <div className="p-4 border-b border-zinc-200 flex items-center justify-between bg-zinc-50">
              <div className="font-semibold text-xs tracking-wider uppercase text-zinc-700 flex items-center gap-2">
                <Icons.Tasks /> 后台任务队列
              </div>
              <button onClick={() => setIsTasksOpen(false)} className="text-zinc-400 hover:text-zinc-900 p-1">
                ✕
              </button>
            </div>
            <div className="flex-1 p-4 space-y-4 overflow-y-auto divide-y divide-zinc-100">
              <div>
                <div className="flex justify-between items-start mb-1">
                  <span className="text-sm font-medium text-zinc-800">全量语义向量化 (Embeddings)</span>
                  <span className="text-[10px] font-mono text-amber-700 bg-amber-50 px-1.5 py-0.5 rounded border border-amber-200">运行中</span>
                </div>
                <div className="text-xs text-zinc-500 mb-2 font-mono">Model: jina-embeddings-v2</div>
                <div className="h-1.5 w-full bg-zinc-100 rounded-full overflow-hidden">
                  <div className="h-full bg-zinc-900 w-[72%] animate-pulse"></div>
                </div>
                <div className="text-[10px] font-mono text-zinc-400 mt-1 text-right">72% (38/53 files)</div>
              </div>

              <div className="pt-4">
                <div className="flex justify-between items-start mb-1">
                  <span className="text-sm font-medium text-zinc-800">视觉 OCR 与图像理解</span>
                  <span className="text-[10px] font-mono text-rose-700 bg-rose-50 px-1.5 py-0.5 rounded border border-rose-200">失败等待重试</span>
                </div>
                <div className="text-xs text-zinc-500 mb-2 font-mono">Target: 差旅报销发票_8842.jpg</div>
                <div className="p-2.5 bg-rose-50/50 border border-rose-100 rounded-lg text-[11px] font-mono text-rose-800">
                  Error: Provider timeout after 30s. Check API key.
                </div>
                <button className="mt-2 text-xs font-medium text-zinc-800 bg-white border border-zinc-300 px-3 py-1.5 rounded-lg hover:bg-zinc-50 transition-colors shadow-sm">
                  立即重试任务
                </button>
              </div>
            </div>
          </div>
          <div className="flex-1 bg-black/20 backdrop-blur-xs" onClick={() => setIsTasksOpen(false)}></div>
        </div>
      )}

      {/* 主对话工作区画布 */}
      <main className="flex-1 overflow-y-auto px-6 py-8">
        <div className="max-w-4xl mx-auto space-y-6">
          {stream.map((block) => (
            <div key={block.id} className={`flex ${block.role === 'user' ? 'justify-end' : 'justify-start'}`}>
              {block.role === 'user' ? (
                <div className="max-w-2xl bg-zinc-900 text-white px-5 py-3 rounded-2xl rounded-tr-sm text-sm font-medium shadow-sm">
                  {block.content}
                </div>
              ) : (
                <div className="max-w-3xl w-full">
                  {block.type === 'text' && (
                    <div className="text-sm text-zinc-800 leading-relaxed pl-3 border-l-2 border-zinc-300">
                      {block.content}
                    </div>
                  )}
                  {block.type === 'thoughts' && (
                    <ThoughtProcessBlock thoughts={block.thoughts} isThinking={false} />
                  )}
                  {block.type === 'tool-call' && (
                    <ToolCallBadge toolName={block.toolName} args={block.args} status={block.status} result={block.result} />
                  )}
                  {block.type === 'action-auth' && (
                    <div className="my-3 border-2 border-zinc-900 rounded-xl overflow-hidden bg-white shadow-md max-w-xl">
                      <div className="px-4 py-2.5 bg-zinc-900 text-white flex items-center gap-2">
                        <Icons.ShieldAlert />
                        <span className="text-xs font-semibold tracking-wide uppercase">高风险写操作拦截授权</span>
                      </div>
                      <div className="p-4 space-y-3">
                        <p className="text-sm text-zinc-700">
                          Agent 即将执行文件批量移动，请核对目标：
                        </p>
                        <div className="bg-zinc-50 p-3 rounded-lg border border-zinc-200 font-mono text-xs text-zinc-700 space-y-1">
                          <div><span className="text-zinc-400"># 目标路径:</span> {block.destination}</div>
                          <div><span className="text-zinc-400"># 操作对象:</span> {block.targets.join(', ')}</div>
                        </div>
                      </div>
                      <div className="px-4 py-3 bg-zinc-50 border-t border-zinc-200 flex gap-3">
                        <button onClick={() => setStream(p => [...p, { id: Date.now(), role: 'system', type: 'text', content: '✓ 授权已通过，操作执行成功。' }])} className="px-4 py-2 bg-zinc-900 text-white text-xs font-medium rounded-lg hover:bg-zinc-800 transition-colors shadow-sm">
                          确认并执行
                        </button>
                        <button onClick={() => setStream(p => [...p, { id: Date.now(), role: 'system', type: 'text', content: '✗ 用户已拒绝该操作。' }])} className="px-4 py-2 bg-white text-zinc-700 border border-zinc-300 text-xs font-medium rounded-lg hover:bg-zinc-50 transition-colors">
                          驳回
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              )}
            </div>
          ))}
          <div ref={streamEndRef} className="h-16" />
        </div>
      </main>

      {/* 底部控制输入舱 (Omnibar & Thinking Level) */}
      <footer className="p-6 shrink-0 bg-gradient-to-t from-white via-white to-transparent sticky bottom-0 z-20">
        <div className="max-w-3xl mx-auto">
          <form
            onSubmit={handleCommand}
            className="relative flex flex-col bg-white border border-zinc-200 rounded-2xl shadow-xl focus-within:border-zinc-400 transition-all overflow-hidden"
          >
            {/* 思考等级控制器 */}
            <div className="flex items-center justify-between px-4 py-2 border-b border-zinc-100 bg-zinc-50/80">
              <div className="flex items-center gap-3">
                <span className="text-[10px] font-semibold text-zinc-500 uppercase tracking-wider">推理层级 (Thinking):</span>
                {['Normal', 'Deep', 'Exhaustive'].map(level => (
                  <label key={level} className="flex items-center gap-1.5 cursor-pointer group">
                    <input
                      type="radio"
                      name="thinkLevel"
                      checked={thinkLevel === level}
                      onChange={() => setThinkLevel(level)}
                      className="w-3 h-3 text-zinc-900 border-zinc-300 focus:ring-zinc-900"
                    />
                    <span className={`text-xs font-medium transition-colors ${thinkLevel === level ? 'text-zinc-900 font-semibold' : 'text-zinc-500 group-hover:text-zinc-700'}`}>
                      {level}
                    </span>
                  </label>
                ))}
              </div>
              <span className="text-[10px] font-mono text-zinc-400">Agent v2.6-local</span>
            </div>

            <div className="flex items-center px-4 py-3">
              <input
                type="text"
                value={input}
                onChange={(e) => setInput(e.target.value)}
                placeholder="自然语言下达指令，或寻找知识边界..."
                className="flex-1 bg-transparent border-none outline-none text-sm text-zinc-900 placeholder:text-zinc-400 font-medium py-1"
                autoFocus
              />
              <button
                type="submit"
                disabled={!input.trim() || isProcessing}
                className={`ml-3 p-2 rounded-xl transition-colors ${input.trim() && !isProcessing ? 'bg-zinc-900 text-white shadow-sm hover:bg-zinc-800' : 'bg-zinc-100 text-zinc-400'}`}
              >
                <Icons.ArrowRight />
              </button>
            </div>
          </form>

          <div className="mt-3 flex flex-wrap gap-2 justify-center">
            {['查找发票', '整理财务目录', '全盘扫描索引'].map(cmd => (
              <button
                key={cmd}
                type="button"
                onClick={() => setInput(cmd)}
                className="text-[11px] font-medium text-zinc-600 bg-zinc-50 border border-zinc-200 px-3 py-1.5 rounded-full hover:text-zinc-900 hover:bg-zinc-100 transition-colors shadow-2xs"
              >
                {cmd}
              </button>
            ))}
          </div>
        </div>
      </footer>

    </div>
  );
}