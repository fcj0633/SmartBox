package cn.smartbox;
final class TestDatabaseGuard {
    static void require(String profile,String url,String database,String currentUser,String consent){
        if(!"test".equals(profile)||url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]{1,5}/smartbox_test"+"(?:\\?.*)?")
            ||!"smartbox_test".equals(database)||!"smartbox_test_app@localhost".equals(currentUser)||!"true".equals(consent))
            throw new IllegalStateException("Refusing cleanup outside the explicit project test database");
    }
}
