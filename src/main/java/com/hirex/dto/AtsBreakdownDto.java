package com.hirex.dto;

import java.util.List;

/**
 * Detailed breakdown of how an applicant's ATS score was computed.
 * Powers the "ATS Breakdown" section of the Candidate Details page.
 */
public class AtsBreakdownDto {

    private List<String> matchedSkills;
    private List<String> missingSkills;
    private int keywordMatchPercent;   // matchedSkills / (matchedSkills + missingSkills) * 100
    private boolean experienceMatch;
    private String experienceNote;
    private boolean educationMatch;
    private String educationNote;
    private List<String> aiSuggestions;

    public AtsBreakdownDto() {}

    public List<String> getMatchedSkills() { return matchedSkills; }
    public void setMatchedSkills(List<String> matchedSkills) { this.matchedSkills = matchedSkills; }

    public List<String> getMissingSkills() { return missingSkills; }
    public void setMissingSkills(List<String> missingSkills) { this.missingSkills = missingSkills; }

    public int getKeywordMatchPercent() { return keywordMatchPercent; }
    public void setKeywordMatchPercent(int keywordMatchPercent) { this.keywordMatchPercent = keywordMatchPercent; }

    public boolean isExperienceMatch() { return experienceMatch; }
    public void setExperienceMatch(boolean experienceMatch) { this.experienceMatch = experienceMatch; }

    public String getExperienceNote() { return experienceNote; }
    public void setExperienceNote(String experienceNote) { this.experienceNote = experienceNote; }

    public boolean isEducationMatch() { return educationMatch; }
    public void setEducationMatch(boolean educationMatch) { this.educationMatch = educationMatch; }

    public String getEducationNote() { return educationNote; }
    public void setEducationNote(String educationNote) { this.educationNote = educationNote; }

    public List<String> getAiSuggestions() { return aiSuggestions; }
    public void setAiSuggestions(List<String> aiSuggestions) { this.aiSuggestions = aiSuggestions; }
}
