package appapi

import "testing"

func TestConvertYRCToEnhancedLRC(t *testing.T) {
	yrc := `{"t":0,"c":[{"tx":"metadata"}]}
[12340,3500](12340,210,0)Got (12550,420,0)every(12970,310,0)one
[20000,1200](20000,600,0)你(20600,600,0)好
[30000,500](30000,500,0)End`
	translation := `[00:12.10]你好，大家
[00:20.45]问候
[00:30.501]too far`
	want := "[00:12.340]<00:12.340>Got <00:12.550>every<00:12.970>one[00:15.840]\n" +
		"[00:12.340]你好，大家\n" +
		"[00:20.000]<00:20.000>你<00:20.600>好[00:21.200]\n" +
		"[00:20.000]问候\n" +
		"[00:30.000]<00:30.000>End[00:30.500]\n"
	if got := convertYRCToEnhancedLRC(yrc, translation); got != want {
		t.Errorf("converted lyrics:\n%q\nwant:\n%q", got, want)
	}
}

func TestConvertYRCTranslationUsedOnlyOnce(t *testing.T) {
	yrc := "[1000,100](1000,100,0)one\n[1200,100](1200,100,0)two"
	want := "[00:01.000]<00:01.000>one[00:01.100]\n" +
		"[00:01.000]translation\n" +
		"[00:01.200]<00:01.200>two[00:01.300]\n"
	if got := convertYRCToEnhancedLRC(yrc, "[00:01.1]translation"); got != want {
		t.Errorf("converted lyrics:\n%q\nwant:\n%q", got, want)
	}
}

func TestConvertYRCWithoutWords(t *testing.T) {
	if got := convertYRCToEnhancedLRC("{\"t\":0}\n[1000,200]plain text\n[2000,100](2000,100,0) ", ""); got != "" {
		t.Errorf("expected fallback for unusable lyrics, got %q", got)
	}
}
