package de.spraener.prjxp.chuno.docs.model;

public enum ConversionAccuracy {
    ANALYTIC(4),         // Code-based, 100% accurate (like ascii text extraction)
    AI_DRIVEN(3),        // AI based with high-quality models but a small risk (like gemini, chatGPT etc)
    LOCAL_AI_DRIVEN(2),  // local AI base lower quality models with a moderate risk of information lost (like llava, gemma4)
    INFORMATION_LOST(1); // heavily information lost like summaries or thumb nails

    private final int rank;
    ConversionAccuracy(int rank) { this.rank = rank; }
    public int getRank() { return rank; }
}
