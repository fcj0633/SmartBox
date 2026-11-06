package cn.smartbox.common;
public final class Inputs {
    private Inputs(){}
    public static boolean length(String s,int min,int max) {
        if(s==null)return false;
        for(int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            if(Character.isHighSurrogate(c)){if(i+1>=s.length()||!Character.isLowSurrogate(s.charAt(++i)))return false;}
            else if(Character.isLowSurrogate(c))return false;
        }
        int count=s.codePointCount(0,s.length());return count>=min&&count<=max;
    }
    public static boolean id(String s){return s!=null&&s.matches("[A-Za-z0-9_-]{1,48}");}
}
