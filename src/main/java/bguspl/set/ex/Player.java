package bguspl.set.ex;

import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.BlockingQueue;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    //our code
    /**
     * tokensLeft = the number of tokens player playerId is not using right now.
     */
    protected int tokensLeft; 

    /**
     * blocking queue that holds incoming actions. the queue is of capacity 3
     */
    protected PlayerInputQueue incomingActionsQueue;

    /**
     * the time the player is frozen until.
     */
    protected volatile long timeToFreeze;

    /**
     * A flag marking that the player has selected a set and is waiting for the dealer to check it.
     */
    protected volatile boolean waitingForDealerCheck;


    private Dealer dealer;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;

        this.dealer = dealer;
        this.tokensLeft = env.config.featureSize;
        this.incomingActionsQueue = new PlayerInputQueue(env.config.featureSize);
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();
        while (!terminate) {
            // TODO implement main player loop
            //taking an action from the actions queue.
            Integer slot = incomingActionsQueue.take();
            //if the game is done, exit the loop.
            if (terminate){
                break;
            }
            //if there is a token on the slot -> remove the token
            if (this.table.playersTokens[this.id][slot]){
                this.removePlayerToken(slot);
            }

            else if (tokensLeft>0){
                this.table.beforeRead();
                this.placePlayerToken(slot);
                // if the third token was placed, the newly formed set is sent to the dealer for checking.
                if (tokensLeft==0 && !this.waitingForDealerCheck){
                    this.waitingForDealerCheck = true;
                    dealer.addSetToCheck(this.getSet());
                    dealer.wakeDealerThread();
                }
                this.table.afterRead();    
            }
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                //Generates a random integer between 0 (inclusive) and tableSize (exclusive)
                Random random = new Random();
                this.keyPressed(random.nextInt(env.config.tableSize)); 
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        this.terminate=true;
        //terminating the actions queue.
        this.incomingActionsQueue.terminate();
        //ending the player thread gracfully.
        try {
            this.playerThread.join();
        } catch (InterruptedException e) {}
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        //only if the player is not frozen, the action is added to the queue.
        if (!this.waitingForDealerCheck && timeToFreeze-System.currentTimeMillis()<0){
            this.incomingActionsQueue.put(slot);
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        env.ui.setFreeze(this.id, env.config.pointFreezeMillis);
        this.timeToFreeze = System.currentTimeMillis() + env.config.pointFreezeMillis;

        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        env.ui.setFreeze(this.id, env.config.penaltyFreezeMillis);
        this.timeToFreeze = System.currentTimeMillis() + env.config.penaltyFreezeMillis;
    }

    public int score() {
        return score;
    }

    /**
     * return the PlayerSet of the Player
     */
    private PlayerSet getSet(){
        int []setSlots = new int [env.config.featureSize];
        int []setCards = new int [env.config.featureSize];
        int index=0;
        for (int i =0; i < this.table.playersTokens[this.id].length ; i++){
            if (this.table.playersTokens[this.id][i]){
                setSlots [index] = i;
                setCards[index] = table.slotToCard[i];
                index++;
            }
        }
        return new PlayerSet(this.id, setSlots, setCards);
    }

    /**
     * Method that wraps table.removeToken
     */
    protected synchronized void removePlayerToken (int slot){
        if (!this.waitingForDealerCheck && this.table.removeToken(this.id, slot)){
            this.tokensLeft++;
        }
    }

    /**
     * Method that wraps table.placeToken
     */
    protected synchronized void placePlayerToken (int slot){      
        if (this.tokensLeft > 0 && !this.waitingForDealerCheck && !this.table.playersTokens[this.id][slot] && this.table.slotToCard[slot]!=-1){
            this.table.placeToken(this.id, slot);
            this.tokensLeft--;
        }
    }

    /**
     * removes all of the tokens the player have on the table.
     */
    protected synchronized void removeAllPlayerTokens (){
        for (int i = 0 ; i < this.table.playersTokens[this.id].length ; i++){
            this.removePlayerToken(i);
        }
    }
}
