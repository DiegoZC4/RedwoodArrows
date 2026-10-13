package org.redwoodarrows;

public final class DistanceStepsTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static void near(double actual,double expected,String why){check(Math.abs(actual-expected)<1e-8,why+": "+actual+" != "+expected);}
    static double step(double yards,int n){return Practice.fromMeters(DistanceSteps.at(Practice.toMeters(yards,"yd"),"yd",n),"yd");}
    public static void main(String[] args){
        near(step(19,1),20,"One-yard step below twenty");near(step(20,1),25,"Five-yard step above twenty");near(step(25,-1),20,"Return to threshold");near(step(20,-1),19,"Fine downward step at threshold");near(step(19,3),30,"Cross threshold upwards");near(step(30,-3),19,"Cross threshold downwards");
        near(step(23,0),23,"Odd marked Hunter distance preserved");near(step(23,1),25,"Odd distance next stop");near(step(23,-1),20,"Odd distance previous stop");near(step(10.5,1),11,"Fractional distance next stop");near(step(10.5,-1),10,"Fractional distance previous stop");
        near(step(1,-10),1,"Lower bound never wraps");near(step(.5,-10),.5,"Existing sub-yard custom value not increased on decrement");near(DistanceSteps.at(1000,"yd",100),1000,"Upper bound remains valid");near(DistanceSteps.at(20,"m",1),25,"Existing metric preference stays metric");
        check(Double.isNaN(DistanceSteps.at(Double.NaN,"yd",0)),"Unknown stays unknown on no-op");near(Practice.fromMeters(DistanceSteps.at(Double.NaN,"yd",1),"yd"),20,"First intentional step offers twenty yards");near(Practice.fromMeters(DistanceSteps.at(Double.NaN,"yd",-1),"yd"),20,"Unknown can be set in either direction");
        for(double value:new double[]{1,7,19,20,25,50,80,23,10.5}){near(step(value,0),value,"Anchor restored exactly after out-and-back");for(int n=-50;n<=50;n++){double x=DistanceSteps.at(Practice.toMeters(value,"yd"),"yd",n);check(Practice.distance(x),"Every stop valid");if(n>0)check(x>=Practice.toMeters(value,"yd")-1e-8,"Increasing monotone");if(n<0)check(x<=Practice.toMeters(value,"yd")+1e-8,"Decreasing monotone");}}
        check(DistanceSteps.same(Double.NaN,Double.NaN),"Unknown no-op equality");System.out.println("Distance step checks passed: "+checks);
    }
}
