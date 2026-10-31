package com.tencent.wxcloudrun.game.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 一张扑克牌 */
public class Card {
    @JsonProperty("cardId")
    private String cardId;
    private Suit suit;
    private Rank rank;
    @JsonProperty("deckNo")
    private int deckNo;

    public Card() {}

    public Card(String cardId, Suit suit, Rank rank, int deckNo) {
        this.cardId = cardId;
        this.suit = suit;
        this.rank = rank;
        this.deckNo = deckNo;
    }

    public String getCardId() { return cardId; }
    public void setCardId(String cardId) { this.cardId = cardId; }
    public Suit getSuit() { return suit; }
    public void setSuit(Suit suit) { this.suit = suit; }
    public Rank getRank() { return rank; }
    public void setRank(Rank rank) { this.rank = rank; }
    public int getDeckNo() { return deckNo; }
    public void setDeckNo(int deckNo) { this.deckNo = deckNo; }

    public int getScore() { return rank.getScore(); }
}
