package id.maou.extras;

import java.util.HashSet;
import java.util.Set;

/** Data per pemain; disimpan sebagai JSON di folder dunia. */
public class PlayerData {
    public int kills;
    public int deaths;
    public long coins = 100;
    public String cosmetic = "";
    public Set<String> owned = new HashSet<>();
}
