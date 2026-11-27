package ac.navro.proxy.util;

import java.util.Arrays;
import java.util.Locale;


public final class ForwardingLatency {
    private final Window upstream=new Window(),downstream=new Window();
    public void record(boolean fromBackend,long nanos) { (fromBackend?downstream:upstream).record(nanos); }
    public Sample sample(boolean fromBackend) { return (fromBackend?downstream:upstream).sample(); }
    public record Sample(int count,double averageMs,double p95Ms,double maximumMs) {
        public String display() {
            return count==0?"暂无样本":String.format(Locale.ROOT,"平均 %.3f / P95 %.3f / 最大 %.3f ms（最近 %d 次）",averageMs,p95Ms,maximumMs,count);
        }
    }
    private static final class Window {
        private final long[] values=new long[256];
        private int count,next;
        synchronized void record(long nanos) {
            values[next]=Math.max(0,nanos);next=(next+1)%values.length;count=Math.min(count+1,values.length);
        }
        synchronized Sample sample() {
            if(count==0)return new Sample(0,0,0,0);
            long[] sorted=Arrays.copyOf(values,count);Arrays.sort(sorted);
            double sum=0;for(long value:sorted)sum+=value;
            return new Sample(count,sum/count/1_000_000D,sorted[(int)Math.ceil(count*.95)-1]/1_000_000D,sorted[count-1]/1_000_000D);
        }
    }
}
