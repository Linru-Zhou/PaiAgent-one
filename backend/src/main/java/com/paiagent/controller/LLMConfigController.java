package com.paiagent.controller;

import com.paiagent.common.Result;
import com.paiagent.entity.LLMGlobalConfig;
import com.paiagent.security.SecretCodec;
import com.paiagent.service.LLMGlobalConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 全局模型配置管理控制器。
 */
@Tag(name = "全局模型配置接口")
@RestController
@RequestMapping("/api/llm-config")
public class LLMConfigController {

    @Autowired
    private LLMGlobalConfigService llmGlobalConfigService;

    @Operation(summary = "获取指定提供商的配置列表")
    @GetMapping("/{provider}")
    public Result<List<LLMGlobalConfig>> listByProvider(@PathVariable String provider) {
        List<LLMGlobalConfig> configs = llmGlobalConfigService.listByProvider(provider);
        return Result.success(maskAll(configs));
    }

    @Operation(summary = "获取所有配置列表")
    @GetMapping
    public Result<List<LLMGlobalConfig>> listAll() {
        List<LLMGlobalConfig> configs = llmGlobalConfigService.list();
        return Result.success(maskAll(configs));
    }

    @Operation(summary = "获取配置详情")
    @GetMapping("/detail/{id}")
    public Result<LLMGlobalConfig> getById(@PathVariable Long id) {
        LLMGlobalConfig config = llmGlobalConfigService.getById(id);
        if (config == null) {
            return Result.error("配置不存在");
        }
        return Result.success(masked(config));
    }

    @Operation(summary = "获取指定提供商的默认配置")
    @GetMapping("/default/{provider}")
    public Result<LLMGlobalConfig> getDefaultConfig(@PathVariable String provider) {
        LLMGlobalConfig config = llmGlobalConfigService.getDefaultConfig(provider);
        return Result.success(masked(config));
    }

    @Operation(summary = "保存配置（新增或更新）")
    @PostMapping
    public Result<LLMGlobalConfig> saveConfig(@RequestBody LLMGlobalConfig config) {
        try {
            LLMGlobalConfig saved = llmGlobalConfigService.saveConfig(config);
            return Result.success(masked(saved));
        } catch (IllegalArgumentException e) {
            return Result.error("保存配置失败: " + e.getMessage());
        } catch (DuplicateKeyException e) {
            return Result.error("保存配置失败: 同一供应商下的配置别名不能重复");
        } catch (Exception e) {
            return Result.error("保存配置失败，请稍后重试");
        }
    }

    @Operation(summary = "删除配置")
    @DeleteMapping("/{id}")
    public Result<Void> deleteConfig(@PathVariable Long id) {
        llmGlobalConfigService.deleteConfig(id);
        return Result.success();
    }

    @Operation(summary = "设置默认配置")
    @PostMapping("/{id}/default")
    public Result<Void> setDefaultConfig(@PathVariable Long id) {
        try {
            llmGlobalConfigService.setDefaultConfig(id);
            return Result.success();
        } catch (Exception e) {
            return Result.error("设置默认配置失败: " + e.getMessage());
        }
    }

    /** 接口不返回真实密钥；拷贝一份再脱敏，避免改动到 service 持有的实体。 */
    private static LLMGlobalConfig masked(LLMGlobalConfig source) {
        if (source == null) {
            return null;
        }
        LLMGlobalConfig copy = new LLMGlobalConfig();
        BeanUtils.copyProperties(source, copy);
        copy.setApiKey(SecretCodec.mask(source.getApiKey()));
        return copy;
    }

    private static List<LLMGlobalConfig> maskAll(List<LLMGlobalConfig> configs) {
        return configs.stream().map(LLMConfigController::masked).collect(Collectors.toList());
    }
}
