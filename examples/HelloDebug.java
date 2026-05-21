import java.util.ArrayList;
import java.util.List;

public class HelloDebug {

    private int counter = 0;
    private final String name;
    private final List<User> users = new ArrayList<>();

    public HelloDebug(String name) {
        this.name = name;
        users.add(new User(1, "alice", 30));
        users.add(new User(2, "bob", 17));
        users.add(new User(3, "carol", 42));
    }

    public String describe() {
        return name + " counter=" + counter + " users=" + users.size();
    }

    public int loop() throws InterruptedException {
        while (true) {
            counter++;
            int total = sumAges();
            if (counter % 5 == 0) {
                maybeThrow(counter);
            }
            Thread.sleep(500);
        }
    }

    private int sumAges() {
        int total = 0;
        for (User u : users) {
            total += u.getAge();
        }
        return total;
    }

    private void maybeThrow(int n) {
        if (n == 25) {
            throw new IllegalStateException("counter reached " + n);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("HelloDebug starting; listening for JDWP attach...");
        new HelloDebug(args.length > 0 ? args[0] : "demo").loop();
    }

    static class User {
        final int id;
        final String name;
        int age;
        User(int id, String name, int age) { this.id = id; this.name = name; this.age = age; }
        public int getAge() { return age; }
        public String getName() { return name; }
        @Override public String toString() { return "User(" + id + "," + name + "," + age + ")"; }
    }
}
