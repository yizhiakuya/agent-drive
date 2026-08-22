"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { getConfig, listModels } from "@/lib/api/config";
import { supportsInlineImages } from "@/lib/model-capabilities";

/** 独立管理模型选择器生命周期，避免和活动聊天流状态耦合。 */
export function useModelCatalog(configuredModel: string) {
  const [selectedModel, setSelectedModel] = useState(configuredModel);
  const [providerType, setProviderType] = useState("");
  const [configuredSupportsImages, setConfiguredSupportsImages] = useState<boolean | undefined>();
  const [modelCapabilities, setModelCapabilities] = useState<Record<string, boolean>>({});
  const [modelOptions, setModelOptions] = useState<string[]>(configuredModel ? [configuredModel] : []);
  const [modelsOpen, setModelsOpen] = useState(false);
  const [modelsLoading, setModelsLoading] = useState(false);
  const [modelsLoaded, setModelsLoaded] = useState(false);
  const [modelLoadError, setModelLoadError] = useState("");
  const configuredModelRef = useRef(configuredModel);
  const requestRef = useRef(0);
  const loadingRef = useRef(false);

  useEffect(() => {
    if (configuredModelRef.current === configuredModel) return;
    configuredModelRef.current = configuredModel;
    requestRef.current += 1;
    loadingRef.current = false;
    setModelsLoading(false);
    setModelsOpen(false);
    setSelectedModel(configuredModel);
    setModelOptions(configuredModel ? [configuredModel] : []);
    setProviderType("");
    setConfiguredSupportsImages(undefined);
    setModelCapabilities({});
    setModelsLoaded(false);
    setModelLoadError("");
  }, [configuredModel]);

  useEffect(() => {
    let active = true;
    void getConfig().then((config) => {
      if (!active) return;
      setProviderType(config.llm?.type || "");
      setConfiguredSupportsImages(config.llm?.supports_images);
    }).catch(() => {
      // 配置读取失败时继续使用保守的模型名称兜底。
    });
    return () => { active = false; };
  }, [configuredModel]);

  useEffect(() => () => {
    requestRef.current += 1;
    loadingRef.current = false;
  }, []);

  const loadModels = useCallback(async () => {
    if (loadingRef.current) return;
    loadingRef.current = true;
    const request = ++requestRef.current;
    const requestedModel = selectedModel;
    setModelsLoading(true);
    setModelLoadError("");
    try {
      const config = await getConfig();
      if (request !== requestRef.current) return;
      const llm = config.llm;
      if (!llm) throw new Error("尚未配置聊天模型");
      setProviderType(llm.type);
      setConfiguredSupportsImages(llm.supports_images);
      setModelCapabilities({});
      const result = await listModels({ type: llm.type, base_url: llm.base_url, api_key: "" });
      if (request !== requestRef.current) return;
      if (!result.ok || !result.models?.length) {
        throw new Error(result.error || "当前服务商没有返回可用模型");
      }
      setModelOptions((current) => Array.from(new Set([
        requestedModel || llm.model,
        ...current,
        ...result.models!,
      ].filter(Boolean))));
      setModelCapabilities(result.model_capabilities || {});
      setModelsLoaded(true);
    } catch (error) {
      if (request === requestRef.current) {
        setModelLoadError(error instanceof Error ? error.message : String(error));
      }
    } finally {
      if (request === requestRef.current) {
        loadingRef.current = false;
        setModelsLoading(false);
      }
    }
  }, [selectedModel]);

  const modelSupportsImages = modelCapabilities[selectedModel]
    ?? supportsInlineImages(providerType, selectedModel,
      selectedModel === configuredModel ? configuredSupportsImages : undefined);

  return {
    selectedModel,
    setSelectedModel,
    modelOptions,
    modelsOpen,
    setModelsOpen,
    modelsLoading,
    modelsLoaded,
    modelLoadError,
    loadModels,
    modelSupportsImages,
  };
}
