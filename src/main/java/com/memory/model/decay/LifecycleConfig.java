package com.memory.model.decay;

/**
 * 生命周期配置
 */
public class LifecycleConfig {
    private int staleAfterDays = 14;
    private int archiveAfterDays = 30;
    private double purgeWhenImportanceBelow = 0.1;
    private int purgeWhenStaleDays = 60;

    public int getStaleAfterDays() { return staleAfterDays; }
    public void setStaleAfterDays(int staleAfterDays) { this.staleAfterDays = staleAfterDays; }

    public int getArchiveAfterDays() { return archiveAfterDays; }
    public void setArchiveAfterDays(int archiveAfterDays) { this.archiveAfterDays = archiveAfterDays; }

    public double getPurgeWhenImportanceBelow() { return purgeWhenImportanceBelow; }
    public void setPurgeWhenImportanceBelow(double purgeWhenImportanceBelow) {
        this.purgeWhenImportanceBelow = purgeWhenImportanceBelow;
    }

    public int getPurgeWhenStaleDays() { return purgeWhenStaleDays; }
    public void setPurgeWhenStaleDays(int purgeWhenStaleDays) { this.purgeWhenStaleDays = purgeWhenStaleDays; }
}
