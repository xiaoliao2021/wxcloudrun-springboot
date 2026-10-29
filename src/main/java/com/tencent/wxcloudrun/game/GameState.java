package com.tencent.wxcloudrun.game;

import com.tencent.wxcloudrun.poker.Card;
import com.tencent.wxcloudrun.poker.CardPattern;
import com.tencent.wxcloudrun.poker.RuleBook;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一局对局的运行时状态。
 *
 * 存内存不存库：出牌是高频写，每手都落库没必要；库里只留 game / game_player / game_play
 * 用于复盘与结算。服务重启后内存状态丢失，启动时把库里的进行中对局标记为异常终止（见 GameServiceImpl）。
 *
 * 所有会改状态的入口都在 GameServiceImpl 里对实例加锁，这里只放纯数据 + 判定辅助。
 */
public class GameState {

  public static final int STATUS_PLAYING = 1;
  public static final int STATUS_ENDED = 2;

  /** 一局里一个玩家 */
  public static class Seat {
    private final int seat;
    private final long userId;
    private String nickname;
    private final List<Card> hand = new ArrayList<>();
    /** 本轮捡到的分 */
    private int pickedScore;
    private boolean finished;
    private int rankNo;
    private int scoreChange;
    private boolean win;
    /** 该玩家是否已离开对局（App 切后台/返回大厅后由退局接口标记），服务端会代打 */
    private boolean left;
    /** 是否处于托管（autoHost）：离开后由服务端自动出牌 */
    private boolean autoHost;
    /**
     * 是否已彻底退出该对局：主动离开（牌桌返回房间 / 房间页返回大厅 / 退出房间）时置位。
     * 与 left 的区别：left 只是「不本人操作」，quit 是「出局且不能再回来」——
     * 出局后 /v1/games/current 不再返回这局，play/pass 也会被拒。
     */
    private boolean quit;

    public Seat(int seat, long userId, String nickname) {
      this.seat = seat;
      this.userId = userId;
      this.nickname = nickname == null ? "" : nickname;
    }

    public int getSeat() {
      return seat;
    }

    public long getUserId() {
      return userId;
    }

    public String getNickname() {
      return nickname;
    }

    public void setNickname(String nickname) {
      this.nickname = nickname == null ? "" : nickname;
    }

    public List<Card> getHand() {
      return hand;
    }

    public int getPickedScore() {
      return pickedScore;
    }

    public void addPickedScore(int delta) {
      this.pickedScore += delta;
    }

    public boolean isFinished() {
      return finished;
    }

    public void setFinished(boolean finished) {
      this.finished = finished;
    }

    public int getRankNo() {
      return rankNo;
    }

    public void setRankNo(int rankNo) {
      this.rankNo = rankNo;
    }

    public int getScoreChange() {
      return scoreChange;
    }

    public void setScoreChange(int scoreChange) {
      this.scoreChange = scoreChange;
    }

    public boolean isWin() {
      return win;
    }

    public void setWin(boolean win) {
      this.win = win;
    }

    public boolean isLeft() {
      return left;
    }

    public void setLeft(boolean left) {
      this.left = left;
    }

    public boolean isAutoHost() {
      return autoHost;
    }

    public void setAutoHost(boolean autoHost) {
      this.autoHost = autoHost;
    }

    public boolean isQuit() {
      return quit;
    }

    public void setQuit(boolean quit) {
      this.quit = quit;
    }
  }

  /** 本轮某家的最后一次动作，给中央出牌区展示 */
  public static class PlayEntry {
    private final int seat;
    private final List<Card> cards;
    private final int type;
    private final boolean passed;

    public PlayEntry(int seat, List<Card> cards, int type, boolean passed) {
      this.seat = seat;
      this.cards = cards;
      this.type = type;
      this.passed = passed;
    }

    public int getSeat() {
      return seat;
    }

    public List<Card> getCards() {
      return cards;
    }

    public int getType() {
      return type;
    }

    public boolean isPassed() {
      return passed;
    }
  }

  private final long id;
  private final long roomId;
  private final RuleBook ruleBook;

  private int status = STATUS_PLAYING;
  private int revision;
  private int trickNo = 1;
  private int turnSeat;
  private int leaderSeat;
  private int trickScore;

  private final List<Card> deck = new ArrayList<>();
  private final List<Seat> seats = new ArrayList<>();
  /**
   * 本轮的每一手动作，按出牌先后追加（不是 seat -> 最后一手 的映射）。
   *
   * 一轮里同一家可以出好几次（A 先出 -> B 压 -> A 再压回去），用 Map 会把前面的覆盖掉、
   * 顺序也留在第一次的位置。前端按这个数组从左到右铺，所以必须是最真实的出牌序列。
   * 收轮时清空，正好对上「一轮 = 从出牌到补牌」。
   */
  private final List<PlayEntry> table = new ArrayList<>();
  /** 本轮已经「要不起」的座位 */
  private final Set<Integer> passed = new LinkedHashSet<>();

  private CardPattern topPlay;
  private int topSeat = -1;
  private String reason = "dealt";
  /** 本轮的赢家（收轮后填，供推送文案用） */
  private int trickWinnerSeat = -1;

  public GameState(long id, long roomId, RuleBook ruleBook) {
    this.id = id;
    this.roomId = roomId;
    this.ruleBook = ruleBook;
  }

  public long getId() {
    return id;
  }

  public long getRoomId() {
    return roomId;
  }

  public RuleBook getRuleBook() {
    return ruleBook;
  }

  public int getStatus() {
    return status;
  }

  public void setStatus(int status) {
    this.status = status;
  }

  public boolean isEnded() {
    return status == STATUS_ENDED;
  }

  public int getRevision() {
    return revision;
  }

  public int bumpRevision() {
    return ++revision;
  }

  public int getTrickNo() {
    return trickNo;
  }

  public void nextTrick() {
    this.trickNo += 1;
  }

  public int getTurnSeat() {
    return turnSeat;
  }

  public void setTurnSeat(int turnSeat) {
    this.turnSeat = turnSeat;
  }

  public int getLeaderSeat() {
    return leaderSeat;
  }

  public void setLeaderSeat(int leaderSeat) {
    this.leaderSeat = leaderSeat;
  }

  public int getTrickScore() {
    return trickScore;
  }

  public void addTrickScore(int delta) {
    this.trickScore += delta;
  }

  public void resetTrickScore() {
    this.trickScore = 0;
  }

  public List<Card> getDeck() {
    return deck;
  }

  public List<Seat> getSeats() {
    return seats;
  }

  public List<PlayEntry> getTable() {
    return table;
  }

  public Set<Integer> getPassed() {
    return passed;
  }

  public CardPattern getTopPlay() {
    return topPlay;
  }

  public void setTopPlay(CardPattern topPlay) {
    this.topPlay = topPlay;
  }

  public int getTopSeat() {
    return topSeat;
  }

  public void setTopSeat(int topSeat) {
    this.topSeat = topSeat;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  public int getTrickWinnerSeat() {
    return trickWinnerSeat;
  }

  public void setTrickWinnerSeat(int trickWinnerSeat) {
    this.trickWinnerSeat = trickWinnerSeat;
  }

  /* ---------------- 查询辅助 ---------------- */

  public Seat seatOf(long userId) {
    for (Seat seat : seats) {
      if (seat.getUserId() == userId) {
        return seat;
      }
    }
    return null;
  }

  public Seat seatAt(int seat) {
    for (Seat s : seats) {
      if (s.getSeat() == seat) {
        return s;
      }
    }
    return null;
  }

  public int playerCount() {
    return seats.size();
  }

  /** 还有人没出完手牌（且没被跳过） */
  public boolean hasActivePlayers() {
    for (Seat seat : seats) {
      if (!seat.isFinished()) {
        return true;
      }
    }
    return false;
  }

  /** 除本轮最大者外，其他还能出牌的人是否都已过牌 */
  public boolean allOthersPassed() {
    for (Seat seat : seats) {
      if (seat.isFinished() || seat.getSeat() == topSeat) {
        continue;
      }
      if (!passed.contains(seat.getSeat())) {
        return false;
      }
    }
    return true;
  }
}
